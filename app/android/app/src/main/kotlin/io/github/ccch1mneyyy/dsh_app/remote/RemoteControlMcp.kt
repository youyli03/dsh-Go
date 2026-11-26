package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.github.ccch1mneyyy.dsh_app.phone.PhoneCaller
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The SSH forward targets this per-service, loopback-only endpoint. */
internal class RemoteControlMcp private constructor(private val context: Context, private val launchId: Long) :
    NanoHTTPD("127.0.0.1", 0), Closeable {
    val port: Int get() = listeningPort
    private val tools = io.github.ccch1mneyyy.dsh_app.phone.PhoneToolCatalog.listJson() + nextRoundTool
    private val policy = RemoteControlState.policy(context)
    @Volatile private var sessionId = newSessionId()
    @Volatile private var closed = false
    private val executing = java.util.concurrent.atomic.AtomicBoolean()
    private val execJobs = LinkedHashMap<String, ExecJob>()

    private class ExecJob(val owner: String, var result: Map<String, Any?>) {
        var cancel: (() -> Unit)? = null
        var cancelling = false
        var finishedAt: Long? = null
    }

    @Synchronized private fun callExec(name: String, args: Map<String, Any?>, credential: String): ToolOutput {
        fun output(result: Map<String, Any?>, error: Boolean = false) = ToolOutput(Json.write(result), isError = error)
        fun fail(status: String) = output(mapOf("status" to status), true)
        if (!RemoteControlState.allowExec) return fail("exec_disabled")
        val owner = java.security.MessageDigest.getInstance("SHA-256")
            .digest(credential.toByteArray()).joinToString("") { "%02x".format(it) }
        val now = android.os.SystemClock.elapsedRealtime()
        execJobs.entries.removeAll { (_, job) -> job.finishedAt?.let { now - it >= 600_000L } == true }
        val sourceId = args["requestId"] as? String
        val source = execJobs[sourceId]?.takeIf { it.owner == owner }
        if (name == "dsh_exec_status" || name == "dsh_exec_cancel") {
            if (args.keys != setOf("requestId")) return fail("invalid_arguments")
            val job = source ?: return fail("request_not_found")
            if (name == "dsh_exec_cancel" && job.finishedAt == null && !job.cancelling) {
                job.cancelling = true
                job.result = job.result + ("status" to "cancelling")
                job.cancel?.invoke()
            }
            return output(job.result)
        }
        val resumeSession = if (name == "dsh_exec_continue") {
            val job = source ?: return fail("request_not_found")
            if (job.finishedAt == null) return fail("session_busy")
            job.result["sessionId"] as? String ?: return fail("session_unavailable")
        } else null
        val id = if ("clientRequestId" in args) {
            val supplied = args["clientRequestId"] as? String ?: return fail("invalid_request_id")
            if (runCatching { UUID.fromString(supplied).toString() }.getOrNull() != supplied)
                return fail("invalid_request_id")
            supplied
        } else UUID.randomUUID().toString()
        execJobs[id]?.let { return if (it.owner == owner) output(it.result) else fail("request_not_found") }
        if (execJobs.values.any { it.finishedAt == null }) return fail("exec_busy")
        if (execJobs.size >= 128) return fail("exec_request_capacity")
        val request = if (resumeSession != null) args - setOf("clientRequestId", "requestId") else args - "clientRequestId"
        if (request.keys.any { it != "prompt" && it != "timeout_seconds" }) return fail("invalid_arguments")
        if ((request["prompt"] as? String)?.let { it.isNotBlank() && it.length <= 32_768 } != true)
            return fail("invalid_prompt")
        val job = ExecJob(owner, mapOf("requestId" to id, "status" to "accepted", "pollAfterMs" to 2_000))
        execJobs[id] = job
        Thread({
            val result = runCatching {
                val response = RemoteExecClient(context).execute(request, credential, id, resumeSession,
                    sourceId.takeIf { resumeSession != null }, { update ->
                    synchronized(this) {
                        job.result = update - "text" + ("pollAfterMs" to 2_000)
                        if (update["activeTool"] == "handoff")
                            job.result = job.result + ("phase" to "waiting_for_handoff")
                        if (job.cancelling) job.result = job.result + ("status" to "cancelling")
                    }
                }, { cancel ->
                    synchronized(this) {
                        job.cancel = cancel
                        if (job.cancelling || closed) cancel()
                    }
                })
                @Suppress("UNCHECKED_CAST")
                Json.parse(response.text) as Map<String, Any?>
            }.getOrElse { mapOf("status" to "engine_request_failed") }
            synchronized(this) {
                job.result = (job.result - setOf("activeTool", "phase", "pollAfterMs")) + result + ("requestId" to id)
                job.cancel = null
                job.finishedAt = android.os.SystemClock.elapsedRealtime()
            }
        }, "dsh-remote-exec").start()
        return output(job.result)
    }

    private fun callPhone(name: String, args: Map<String, Any?>, credential: String): ToolOutput {
        fun refusal(message: String) = ToolOutput(message, isError = true)
        if (!executing.compareAndSet(false, true)) return refusal("remote_phone_busy: A remote phone call is already executing")
        val cancellation = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val call = policy.admit(name, phone = true) { cancellation.get()?.invoke() }
        try {
            if (call == null) return refusal("remote_phone_unavailable: Remote phone permission unavailable or phone locked")
            fun allowed() = !closed && launchId == RemoteControlState.launchId &&
                RemoteControlState.state == "connected" && policy.allowed(call) && RemoteControlState.authenticates(credential)
            fun registerCancellation(cancel: () -> Unit) {
                cancellation.set(cancel)
                if (!allowed()) cancel()
            }
            if (name == NEXT_ROUND) return startNextRound(args, ::allowed, ::registerCancellation)
            val output = PhoneControl.get(context).externalCall(roundCaller(), name, args, ::allowed, ::registerCancellation)
            return if (allowed()) output else refusal("Remote phone permission is no longer active")
        } finally {
            if (call != null) policy.finish(call)
            executing.set(false)
        }
    }

    private fun roundCaller() = PhoneCaller(sessionId, "remote", UUID.randomUUID().toString(), 1)

    private fun startNextRound(
        args: Map<String, Any?>,
        allowed: () -> Boolean,
        registerCancellation: (() -> Unit) -> Unit,
    ): ToolOutput {
        fun failure(code: String, message: String) = ToolOutput("$code: $message", isError = true)
        if (args.isNotEmpty()) return failure("invalid_arguments", "This tool takes no arguments.")
        val request = RemoteControlState.RoundRequest()
        registerCancellation { request.decision.cancel(false) }
        if (!RemoteControlState.showRoundRequest(request))
            return failure("next_round_unavailable", "Remote phone permission is no longer active.")
        val notification = RemoteRoundNotification(context, request.id)
        try {
            if (!request.decision.isDone) notification.show()
            val approved = request.decision.get(RemoteControlState.ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!approved) return failure("next_round_denied", "The phone user declined the request.")
            synchronized(this) {
                if (!allowed()) return failure("next_round_unavailable", "Remote phone permission is no longer active.")
                PhoneControl.peek()?.externalEnd(roundCaller())
                if (!allowed()) return failure("next_round_unavailable", "Remote phone permission is no longer active.")
                sessionId = newSessionId()
                return ToolOutput("next_round_started: Start with a fresh screen observation.")
            }
        } catch (_: TimeoutException) {
            return failure("next_round_timeout", "No confirmation on the phone. Open the DSH Go Remote control page to approve a new request.")
        } catch (_: java.util.concurrent.CancellationException) {
            return failure("next_round_unavailable", "Remote phone permission is no longer active.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure("next_round_unavailable", "The request was interrupted.")
        } finally {
            RemoteControlState.clearRoundRequest(request)
            notification.dismiss()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        fun text(status: Response.Status, body: String = "") = newFixedLengthResponse(status, MIME_PLAINTEXT, body)
        if (session.uri != "/mcp") return text(Response.Status.NOT_FOUND)
        val host = session.headers["host"]?.substringBefore(':')
        if (host !in setOf("127.0.0.1", "localhost") || session.headers.containsKey("origin"))
            return text(Response.Status.FORBIDDEN)
        val auth = session.headers["authorization"].orEmpty()
        if (!auth.startsWith("Bearer ") || !RemoteControlState.authenticates(auth.removePrefix("Bearer ")))
            return text(Response.Status.UNAUTHORIZED)
        if (RemoteControlState.state != "connected") return text(Response.Status.SERVICE_UNAVAILABLE)
        if (session.method != Method.POST) return text(Response.Status.METHOD_NOT_ALLOWED).apply { addHeader("Allow", "POST") }
        if (session.headers.containsKey("transfer-encoding")) return text(Response.Status.BAD_REQUEST)
        if (session.headers["content-type"]?.substringBefore(';')?.trim()?.equals("application/json", true) != true)
            return text(Response.Status.BAD_REQUEST)
        val length = session.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..262_144 }
            ?: return text(Response.Status.BAD_REQUEST)
        val body = ByteArray(length)
        try { java.io.DataInputStream(session.inputStream).readFully(body) }
        catch (_: java.io.IOException) { return text(Response.Status.BAD_REQUEST) }
        val candidate = auth.removePrefix("Bearer ")
        if (!RemoteControlState.authenticates(candidate)) return text(Response.Status.UNAUTHORIZED)
        val offered = if (RemoteControlState.allowExec) tools + RemoteExecClient.tool + execJobTools else tools
        val protocol = RemoteMcpProtocol(offered) { name, args ->
            if (closed || launchId != RemoteControlState.launchId || !RemoteControlState.authenticates(candidate))
                ToolOutput("Remote credential was revoked", isError = true)
            else if (name == "dsh_exec") RemoteExecClient(context).execute(args, candidate)
            else if (name in execJobNames) callExec(name, args, candidate)
            else callPhone(name, args, candidate)
        }
        val response = protocol.reply(body.toString(Charsets.UTF_8)) ?: return text(Response.Status.ACCEPTED)
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    override fun close() {
        closed = true
        stop()
        synchronized(this) { PhoneControl.peek()?.externalEnd(roundCaller()) }
    }

    companion object {
        private const val NEXT_ROUND = "start_next_round"
        private val execJobNames = setOf("dsh_exec_start", "dsh_exec_status", "dsh_exec_cancel", "dsh_exec_continue")
        private val execJobTools = execJobNames.map { name ->
            val starts = name == "dsh_exec_start"
            val submits = starts || name == "dsh_exec_continue"
            val properties = buildMap {
                if (!starts) put("requestId", mapOf("type" to "string"))
                if (submits) {
                    put("prompt", mapOf("type" to "string", "maxLength" to 32768))
                    put("timeout_seconds", mapOf("type" to "integer", "minimum" to 1, "maximum" to 3600, "default" to 900))
                    put("clientRequestId", mapOf("type" to "string", "description" to "Optional UUID for idempotent retries."))
                }
            }
            val description = when (name) {
                "dsh_exec_start" -> "Start a detached DSH task. Poll dsh_exec_status with the returned requestId."
                "dsh_exec_status" -> "Read current tool category or final result for a detached DSH task."
                "dsh_exec_cancel" -> "Request cancellation of one detached DSH task."
                else -> "Start a new turn in the session of a completed detached DSH task."
            }
            mapOf("name" to name, "description" to description, "inputSchema" to mapOf(
                "type" to "object", "properties" to properties, "additionalProperties" to false,
                "required" to when { starts -> listOf("prompt"); submits -> listOf("requestId", "prompt"); else -> listOf("requestId") },
            ))
        }
        private val nextRoundTool = mapOf(
            "name" to NEXT_ROUND,
            "description" to "Ask the phone user to approve a fresh remote phone round on the DSH Go Remote control page. " +
                "Waits up to 45 seconds. Use after a stopped round or when starting a new phone task.",
            "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any?>(), "additionalProperties" to false),
        )

        private fun newSessionId() = "remote-phone-${UUID.randomUUID()}"

        fun start(context: Context, launchId: Long): RemoteControlMcp = RemoteControlMcp(context.applicationContext, launchId).also {
            try { it.start(SOCKET_READ_TIMEOUT, true) } catch (failure: Exception) { it.close(); throw failure }
        }
    }
}
