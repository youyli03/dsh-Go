package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal object RemoteControlState {
    const val ROUND_TIMEOUT_SECONDS = 45L

    class RoundRequest {
        val id: String = UUID.randomUUID().toString()
        val decision = CompletableFuture<Boolean>()
    }

    // The MCP call publishes and clears this request. The phone page can only decide its current ID.
    private var roundRequest: RoundRequest? = null

    @Synchronized fun showRoundRequest(request: RoundRequest): Boolean {
        if (roundRequest != null || request.decision.isDone || state != "connected") return false
        roundRequest = request
        return true
    }

    @Synchronized fun clearRoundRequest(request: RoundRequest) {
        if (roundRequest === request) roundRequest = null
    }

    @Synchronized fun decideRound(id: String, approved: Boolean): Boolean {
        val request = roundRequest?.takeIf { it.id == id } ?: return false
        return request.decision.complete(approved)
    }

    @Synchronized private fun pendingRoundId(): String? =
        roundRequest?.takeUnless { it.decision.isDone }?.id

    @Volatile var state = "stopped"
        private set
    @Volatile private var error: String? = null
    @Volatile private var token = ""
    @Volatile var allowExec = false
        private set
    @Volatile var execSession = false
        private set
    @Volatile private var currentPolicy: RemoteControlPolicy? = null

    fun liveOperation(): RemoteLiveOperation? = currentPolicy?.liveOperation()

    @Synchronized fun policy(context: Context): RemoteControlPolicy = currentPolicy ?: RemoteControlPolicy(
        context.applicationContext,
    ).also {
        val enabled = RemoteControlConfig.read(context).optBoolean("phoneControlEnabled", true)
        if (!enabled) it.setMode("blocked")
        currentPolicy = it
    }

    @Volatile var launchId = 0L
        private set

    @Synchronized fun prepare(token: String, allowExec: Boolean, execSession: Boolean): Long {
        this.token = token
        this.allowExec = allowExec
        this.execSession = execSession
        launchId++
        transition("connecting")
        return launchId
    }

    @Synchronized fun replaceToken(token: String) {
        this.token = token
        currentPolicy?.revoke()
    }

    fun authenticates(candidate: String): Boolean {
        val expected = token
        return expected.isNotEmpty() && MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray())
    }

    @Synchronized fun transition(value: String, failure: String? = null) {
        error = failure
        state = value
        when (value) {
            "stopped", "error" -> {
                token = ""
                currentPolicy?.revoke()
            }
            "reconnecting" -> currentPolicy?.cancelPhoneCalls()
        }
    }

    fun status(context: Context): Map<String, Any?> {
        val config = RemoteControlConfig.read(context)
        val current = state
        val policy = policy(context)
        return RemoteControlConfig.publicConfig(config) + mapOf(
            "state" to current, "error" to error, "permission" to policy.mode(),
            "locked" to policy.locked(), "activity" to policy.activity(),
            "roundRequestId" to pendingRoundId(),
            "url" to if (current == "connected") "http://127.0.0.1:${config.optInt("remotePort", 18765)}/mcp" else null,
        )
    }
}
