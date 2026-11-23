package io.github.ccch1mneyyy.dsh_app.remote

import android.content.Context
import android.content.Intent
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

internal object RemoteControlChannel {
    fun install(context: Context, messenger: BinaryMessenger): MethodChannel {
        val app = context.applicationContext
        return MethodChannel(messenger, "dsh_go/remote_control").apply {
            setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "status" -> result.success(RemoteControlState.status(app))
                        "configure" -> {
                            if (RemoteControlState.state !in setOf("stopped", "error")) {
                                result.error("stop_before_configure", null, null)
                                return@setMethodCallHandler
                            }
                            val host = call.argument<String>("host").orEmpty().trim()
                            val username = call.argument<String>("username").orEmpty().trim()
                            val fingerprint = call.argument<String>("fingerprint").orEmpty().trim()
                            val sshPort = call.argument<Int>("sshPort") ?: 22
                            val remotePort = call.argument<Int>("remotePort") ?: 18765
                            require(host.isNotEmpty() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() || it in "/@?#" })
                            require(username.isNotEmpty() && username.length <= 128 && username.none { it.isWhitespace() || it.isISOControl() })
                            require(fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
                            require(sshPort in 1..65535 && remotePort in 1024..65535)
                            val key = call.argument<String>("privateKey")?.takeIf(String::isNotBlank)
                            val passphrase = call.argument<String>("passphrase")
                            require((key?.length ?: 0) <= 32_768 && (passphrase?.length ?: 0) <= 4096)
                            RemoteControlConfig.update(app) {
                                require(key != null || optString("privateKey").isNotEmpty())
                                put("host", host)
                                put("username", username)
                                put("fingerprint", fingerprint)
                                put("sshPort", sshPort)
                                put("remotePort", remotePort)
                                if (key != null) {
                                    put("privateKey", key.trim() + "\n")
                                    put("passphrase", passphrase.orEmpty())
                                }
                                call.argument<Boolean>("autoStart")?.let { put("autoStart", it) }
                                call.argument<Boolean>("allowExec")?.let { put("allowExec", it) }
                                call.argument<Boolean>("execSession")?.let { put("execSession", it) }
                            }
                            result.success(RemoteControlState.status(app))
                        }
                        "start" -> { start(app); result.success(RemoteControlState.status(app)) }
                        "stop" -> {
                            RemoteControlState.transition("stopped")
                            app.stopService(Intent(app, RemoteControlService::class.java))
                            result.success(RemoteControlState.status(app))
                        }
                        "setPermission" -> {
                            val mode = call.argument<String>("permission")
                            require(mode == "blocked" || mode == "confirm")
                            RemoteControlConfig.update(app) { put("phoneControlEnabled", mode == "confirm") }
                            RemoteControlState.policy(app).setMode(mode)
                            result.success(RemoteControlState.status(app))
                        }
                        "decideRound" -> {
                            val decided = RemoteControlState.decideRound(
                                call.argument<String>("id").orEmpty(),
                                call.argument<Boolean>("approved") == true,
                            )
                            if (decided) result.success(null) else result.error("next_round_request_expired", null, null)
                        }
                        "rotateToken" -> {
                            val token = RemoteControlConfig.newToken()
                            RemoteControlConfig.update(app) { put("token", token) }
                            RemoteControlState.replaceToken(token)
                            result.success(token)
                        }
                        "autoStart" -> {
                            if (!checkedAutoStart) {
                                checkedAutoStart = true
                                if (RemoteControlConfig.read(app).optBoolean("autoStart")) start(app)
                            }
                            result.success(RemoteControlState.status(app))
                        }
                        else -> result.notImplemented()
                    }
                } catch (_: IllegalArgumentException) {
                    result.error("invalid_configuration", null, null)
                } catch (_: Exception) {
                    result.error("remote_control_failed", null, null)
                }
            }
        }
    }

    private var checkedAutoStart = false

    private fun start(context: Context) {
        if (RemoteControlState.state !in setOf("stopped", "error")) return
        val config = RemoteControlConfig.read(context)
        require(config.optString("host").isNotEmpty() && config.optString("privateKey").isNotEmpty())
        require(config.optString("token").isNotEmpty())
        val launch = RemoteControlState.prepare(
            config.getString("token"), config.optBoolean("allowExec"), config.optBoolean("execSession"),
        )
        try {
            context.startForegroundService(Intent(context, RemoteControlService::class.java)
                .putExtra(RemoteControlService.LAUNCH, launch))
        } catch (e: Exception) {
            RemoteControlState.transition("error", "service_start_failed")
            throw e
        }
    }
}
