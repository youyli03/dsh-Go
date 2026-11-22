package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import java.security.MessageDigest

internal object RemoteControlState {
    @Volatile var state = "stopped"
        private set
    @Volatile private var error: String? = null
    @Volatile private var token = ""
    @Volatile var allowExec = false
        private set
    @Volatile private var currentPolicy: RemoteControlPolicy? = null

    @Synchronized fun policy(context: Context): RemoteControlPolicy = currentPolicy ?: RemoteControlPolicy(
        context.applicationContext,
    ).also { currentPolicy = it }

    @Volatile var launchId = 0L
        private set

    @Synchronized fun prepare(token: String, allowExec: Boolean): Long {
        this.token = token
        this.allowExec = allowExec
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
            "url" to if (current == "connected") "http://127.0.0.1:${config.optInt("remotePort", 18765)}/mcp" else null,
        )
    }
}
