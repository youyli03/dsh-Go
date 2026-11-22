package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.Closeable

/** The SSH forward targets this per-service, loopback-only endpoint. */
internal class RemoteControlMcp private constructor(private val context: Context) : NanoHTTPD("127.0.0.1", 0), Closeable {
    val port: Int get() = listeningPort
    private val tools = io.github.ccch1mneyyy.dsh_app.phone.PhoneToolCatalog.listJson()
    private val policy = RemoteControlState.policy(context)
    private val sessionId = "remote-phone-${java.util.UUID.randomUUID()}"
    private val executing = java.util.concurrent.atomic.AtomicBoolean()

    private fun callPhone(name: String, args: Map<String, Any?>, credential: String): io.github.ccch1mneyyy.dsh_app.phone.ToolOutput {
        fun refusal(message: String) = io.github.ccch1mneyyy.dsh_app.phone.ToolOutput(message, isError = true)
        if (!executing.compareAndSet(false, true)) return refusal("A remote phone call is already executing")
        val cancellation = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val call = policy.admit(name, phone = true) { cancellation.get()?.invoke() }
        try {
            if (call == null) return refusal("Remote phone permission unavailable or phone locked")
            fun allowed() = RemoteControlState.state == "connected" && policy.allowed(call) && RemoteControlState.authenticates(credential)
            val caller = io.github.ccch1mneyyy.dsh_app.phone.PhoneCaller(sessionId, "remote", java.util.UUID.randomUUID().toString(), 1)
            val output = io.github.ccch1mneyyy.dsh_app.phone.PhoneControl.get(context).externalCall(caller, name, args, ::allowed) { cancel ->
                cancellation.set(cancel)
                if (!allowed()) cancel()
            }
            return if (allowed()) output else refusal("Remote phone permission is no longer active")
        } finally {
            if (call != null) policy.finish(call)
            executing.set(false)
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
            if (!RemoteControlState.authenticates(candidate))
                io.github.ccch1mneyyy.dsh_app.phone.ToolOutput("Remote credential was revoked", isError = true)
            else if (name == "dsh_exec") RemoteExecClient(context).execute(args, candidate)
            else callPhone(name, args, candidate)
        }
        val response = protocol.reply(body.toString(Charsets.UTF_8)) ?: return text(Response.Status.ACCEPTED)
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    override fun close() {
        stop()
        io.github.ccch1mneyyy.dsh_app.phone.PhoneControl.peek()?.externalEnd(
            io.github.ccch1mneyyy.dsh_app.phone.PhoneCaller(sessionId, "remote", "close", 1),
        )
    }

    companion object {
        fun start(context: Context): RemoteControlMcp = RemoteControlMcp(context.applicationContext).also {
            try { it.start(SOCKET_READ_TIMEOUT, true) } catch (failure: Exception) { it.close(); throw failure }
        }
    }
}
