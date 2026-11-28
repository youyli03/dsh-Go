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
    private val appSettings = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    private var cookie: String? = null
    private var cookieProof: String? = null

    fun execute(
        args: Map<String, Any?>, credential: String,
        requestId: String = UUID.randomUUID().toString(),
        resumeSession: String? = null,
        resumeRequest: String? = null,
        progress: (Map<String, Any?>) -> Unit = {},
        registerCancel: (() -> Unit) -> Unit = {},
    ): ToolOutput {
        fun fail(code: String) = ToolOutput(Json.write(mapOf("status" to code)), isError = true)
        if (args.keys.any { it != "prompt" && it != "timeout_seconds" }) return fail("invalid_arguments")
        val prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() && it.length <= 32_768 }
            ?: return fail("invalid_prompt")
        val timeoutSeconds = if ("timeout_seconds" in args) {
            val value = (args["timeout_seconds"] as? Number)?.toDouble()
                ?: return fail("invalid_timeout")
            if (value !in 1.0..3600.0 || value != value.toInt().toDouble()) return fail("invalid_timeout")
            value.toInt()
        } else 900
        if (!RemoteControlState.allowExec || !RemoteControlState.authenticates(credential)) return fail("exec_disabled")
        val origin = runtime.remoteExecOrigin() ?: return fail("engine_unavailable")
        val sessionMode = RemoteControlState.execSession
        if (resumeSession != null && !sessionMode) return fail("session_mode_required")
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
            registerCancel(::cancel)
            if (stopped.get() || !policy.allowed(call) || !RemoteControlState.authenticates(credential)) return fail("cancelled")
            val workspaceId = try {
                (post(origin, "/workspace", emptyMap())["workspaceId"] as? String)?.takeIf(String::isNotBlank)
            } catch (_: Exception) { null }
            if (stopped.get() || !policy.allowed(call)) return fail("cancelled")
            if (workspaceId == null) return fail("workspace_unavailable")
            val request = mutableMapOf("requestId" to requestId, "prompt" to prompt, "timeoutSeconds" to timeoutSeconds)
            if (sessionMode) {
                val sessionId = resumeSession ?: "dsh-go-exec-${UUID.randomUUID()}"
                if (resumeSession == null && !createSession(origin, sessionId, workspaceId)) return fail("session_create_failed")
                if (stopped.get() || !policy.allowed(call)) return fail("cancelled")
                if (resumeSession == null && appSettings.getBoolean("flutter.auto_approve_commands", false) &&
                    !applyAutoApprovePreset(origin, sessionId)) return fail("session_permission_unavailable")
                if (stopped.get() || !policy.allowed(call)) return fail("cancelled")
                request["sessionId"] = sessionId
                if (resumeRequest != null) request["resumeRequestId"] = resumeRequest
            } else {
                request["workspaceId"] = workspaceId
            }
            var result = post(origin, "", request)
            val until = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000L + 10_000L
            while (result["status"] in setOf("accepted", "starting", "running", "cancelling")) {
                if (!runtime.remoteExecCurrent(origin)) return fail("engine_unavailable")
                progress(result.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap())
                policy.updateLiveOperation(call, RemoteLiveOperation.fromWire(result["activeTool"]) ?: RemoteLiveOperation.EXEC)
                if (SystemClock.elapsedRealtime() >= until) {
                    cancel()
                    return ToolOutput(Json.write(result - "activeTool" + ("status" to "deadline_exceeded")), isError = true)
                }
                Thread.sleep(750)
                result = post(origin, "/status", mapOf("requestId" to requestId))
            }
            return if (!policy.allowed(call) || !RemoteControlState.authenticates(credential)) fail("cancelled")
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
        return engineCommand(origin, "session/create",
            mapOf("request" to mapOf("sessionId" to sessionId, "workspaceId" to workspaceId)))?.get("sessionId") == sessionId
    }

    private fun applyAutoApprovePreset(origin: RuntimeController.RemoteExecOrigin, sessionId: String): Boolean {
        val response = engineCommand(origin, "commands/execute", mapOf(
            "agentId" to sessionId, "line" to "/permission danger-full-access",
            "submittedAttachments" to emptyList<Any>(),
        ))
        return (response?.get("result") as? Map<*, *>)?.get("kind") == "success"
    }

    private fun engineCommand(origin: RuntimeController.RemoteExecOrigin, method: String, args: Map<String, Any?>): Map<*, *>? {
        val rpcId = UUID.randomUUID().toString()
        val envelope = mapOf("type" to "client-request", "method" to method, "rpcId" to rpcId,
            "payload" to mapOf("args" to args))
        val response = post(origin, "/api/$method", envelope, engineRpc = true)
        val result = response["result"] as? Map<*, *> ?: return null
        return if (response["rpcId"] == rpcId && result["ok"] == true) result["value"] as? Map<*, *> else null
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
                "properties" to mapOf(
                    "prompt" to mapOf("type" to "string", "maxLength" to 32768),
                    "timeout_seconds" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 3600,
                        "default" to 900, "description" to "Execution deadline in seconds."),
                )))
    }
}
