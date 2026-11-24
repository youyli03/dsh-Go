package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.github.ccch1mneyyy.dsh_app.phone.PhoneCaller
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
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
        val offered = if (RemoteControlState.allowExec) tools + RemoteExecClient.tool else tools
        val protocol = RemoteMcpProtocol(offered) { name, args ->
            if (closed || launchId != RemoteControlState.launchId || !RemoteControlState.authenticates(candidate))
                ToolOutput("Remote credential was revoked", isError = true)
            else if (name == "dsh_exec") RemoteExecClient(context).execute(args, candidate)
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
