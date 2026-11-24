package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import android.os.SystemClock
import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeController
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only the native process receives the engine proof; MCP clients never see it. */
internal class RemoteExecClient(context: Context, private val cancellationExecutor: Executor = cancelWorker) {
    private val runtime = RuntimeController.get(context)
    private val policy = RemoteControlState.policy(context)
    private var cookie: String? = null
    private var cookieProof: String? = null

    fun execute(args: Map<String, Any?>, credential: String): ToolOutput {
        fun fail(code: String) = ToolOutput(Json.write(mapOf("status" to code)), isError = true)
        if (args.keys != setOf("prompt")) return fail("invalid_arguments")
        val prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() && it.length <= 32_768 }
            ?: return fail("invalid_prompt")
        if (!RemoteControlState.allowExec || !RemoteControlState.authenticates(credential)) return fail("exec_disabled")
        val origin = runtime.remoteExecOrigin() ?: return fail("engine_unavailable")
        val sessionMode = RemoteControlState.execSession
        val requestId = UUID.randomUUID().toString()
        val stopped = AtomicBoolean()
        fun cancel() {
            if (stopped.compareAndSet(false, true)) cancellationExecutor.execute {
                if (runtime.remoteExecCurrent(origin)) runCatching { post(origin, "/cancel", mapOf("requestId" to requestId)) }
            }
        }
        val call = policy.admit("dsh_exec", phone = false, ::cancel) ?: return fail("exec_disabled")
        if (!executing.compareAndSet(false, true)) {
            policy.finish(call)
            return fail("exec_busy")
        }
        try {
            if (stopped.get() || !policy.allowed(call) || !RemoteControlState.authenticates(credential)) return fail("cancelled")
            val workspaceId = try {
                (post(origin, "/workspace", emptyMap())["workspaceId"] as? String)?.takeIf(String::isNotBlank)
            } catch (_: Exception) { null }
            if (stopped.get() || !policy.allowed(call)) return fail("cancelled")
            if (workspaceId == null) return fail("workspace_unavailable")
            val request = mutableMapOf("requestId" to requestId, "prompt" to prompt)
            if (sessionMode) {
                val sessionId = "dsh-go-exec-${UUID.randomUUID()}"
                if (!createSession(origin, sessionId, workspaceId)) return fail("session_create_failed")
                if (stopped.get() || !policy.allowed(call)) return fail("cancelled")
                request["sessionId"] = sessionId
            } else {
                request["workspaceId"] = workspaceId
            }
            var result = post(origin, "", request)
            val until = SystemClock.elapsedRealtime() + 310_000L
            while (result["status"] in setOf("accepted", "starting", "running", "cancelling")) {
                if (stopped.get() || !runtime.remoteExecCurrent(origin)) return fail("cancelled")
                policy.updateLiveOperation(call, RemoteLiveOperation.fromWire(result["activeTool"]) ?: RemoteLiveOperation.EXEC)
                if (SystemClock.elapsedRealtime() >= until) {
                    cancel()
                    return ToolOutput(Json.write(result - "activeTool" + ("status" to "deadline_exceeded")), isError = true)
                }
                Thread.sleep(750)
                result = post(origin, "/status", mapOf("requestId" to requestId))
            }
            return if (stopped.get() || !policy.allowed(call)) fail("cancelled")
                else ToolOutput(Json.write(result), isError = result["status"] != "succeeded")
        } catch (_: InterruptedException) {
            if (sessionMode) cancel()
            Thread.currentThread().interrupt()
            return fail("wait_interrupted")
        } catch (_: Exception) {
            if (sessionMode) cancel()
            return fail("engine_request_failed")
        } finally {
            policy.finish(call)
            executing.set(false)
        }
    }

    private fun createSession(origin: RuntimeController.RemoteExecOrigin, sessionId: String, workspaceId: String): Boolean {
        val method = "session/create"
        val rpcId = UUID.randomUUID().toString()
        val envelope = mapOf("type" to "client-request", "method" to method, "rpcId" to rpcId,
            "payload" to mapOf("args" to mapOf("request" to mapOf("sessionId" to sessionId, "workspaceId" to workspaceId))))
        val response = post(origin, "/api/$method", envelope, engineRpc = true)
        val result = response["result"] as? Map<*, *> ?: return false
        return response["rpcId"] == rpcId && result["ok"] == true &&
            (result["value"] as? Map<*, *>)?.get("sessionId") == sessionId
    }

    private fun post(origin: RuntimeController.RemoteExecOrigin, suffix: String, args: Map<String, Any?>,
        engineRpc: Boolean = false): Map<*, *> {
        check(runtime.remoteExecCurrent(origin))
        val base = URI(origin.base)
        check(base.scheme == "http" && base.host == "127.0.0.1" && base.port in 1..65535)
        var retried = false
        while (true) {
            val path = if (engineRpc) suffix else "/dsh-go/v1/exec$suffix"
            val url = URI("http", null, base.host, base.port, path, null, null).toURL()
            val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                if (!engineRpc) connection.setRequestProperty("X-DSH-Go-Exec-Proof", origin.proof)
                connection.setRequestProperty("Cookie", cookieOf(origin))
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = Json.write(args).toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                // The launch token is not accepted here; a fresh engine session mints a new cookie.
                if (status == 401 && !retried) {
                    retried = true
                    cookie = null
                    continue
                }
                check(status in 200..299)
                return Json.parse(read(connection)) as Map<*, *>
            } finally { connection.disconnect() }
        }
    }

    /** `dsh web` only accepts the launch token at `GET /?token=`, answering with a session cookie. */
    private fun cookieOf(origin: RuntimeController.RemoteExecOrigin): String {
        cookie?.takeIf { cookieProof == origin.proof }?.let { return it }
        val base = URI(origin.base)
        val url = URI("http", null, base.host, base.port, "/", "token=" + java.net.URLEncoder.encode(origin.bearer, Charsets.UTF_8), null).toURL()
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            val status = connection.responseCode
            check(status == 302 || status == 303) { "engine login status $status" }
            val raw = connection.headerFields["Set-Cookie"]?.firstOrNull { it.startsWith("dsh-auth-") }
                ?: error("engine login carried no cookie")
            val value = raw.substringBefore(';')
            cookie = value
            cookieProof = origin.proof
            return value
        } finally { connection.disconnect() }
    }

    private fun read(connection: HttpURLConnection): String {
        val response = StringBuilder()
        connection.inputStream.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                check(response.length + count <= 262_144) { "engine response too large" }
                response.append(buffer, 0, count)
            }
        }
        return response.toString()
    }

    companion object {
        private val executing = AtomicBoolean()
        private val cancelWorker = Executors.newSingleThreadExecutor()
        val tool = mapOf("name" to "dsh_exec", "description" to "Run one prompt in a new local DSH session.",
            "inputSchema" to mapOf("type" to "object", "required" to listOf("prompt"), "additionalProperties" to false,
                "properties" to mapOf("prompt" to mapOf("type" to "string", "maxLength" to 32768))))
    }
}
