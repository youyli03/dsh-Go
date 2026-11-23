package io.github.ccch1mneyyy.dsh_app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class RemoteControlService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var started = false
    private var launchId = -1L
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) RemoteControlState.policy(context).cancelPhoneCalls()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            RemoteControlState.transition("stopped")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            launchId = intent?.getLongExtra(LAUNCH, -1L) ?: -1L
            started = true
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Remote control", NotificationManager.IMPORTANCE_LOW),
            )
            startForeground(NOTIFICATION_ID, notification("connecting"))
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            worker.execute(::runTunnel)
        }
        return START_NOT_STICKY
    }

    private fun notification(state: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("DSH Go · Remote control")
        .setContentText(when (state) {
            "connected" -> "SSH tunnel connected"
            "reconnecting" -> "Reconnecting SSH tunnel"
            else -> "Connecting SSH tunnel"
        })
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, "Stop", PendingIntent.getService(
            this, 0, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE,
        )).build())
        .build()

    private fun report(state: String, error: String? = null) = main.post {
        if (!stopped && launchId == RemoteControlState.launchId && RemoteControlState.state != "stopped") {
            RemoteControlState.transition(state, error)
            if (state == "error") stopSelf()
            else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
        }
    }

    private fun runTunnel() {
        val config = try { RemoteControlConfig.read(this) } catch (_: Exception) {
            report("error", "storage_failed"); return
        }
        val endpoint = try { RemoteControlMcp.start(this, launchId) } catch (_: Exception) {
            report("error", "mcp_start_failed"); return
        }
        endpoint.use {
            var connectedBefore = false
            while (!stopped && !Thread.currentThread().isInterrupted) {
                val pin = FingerprintPin(config.optString("fingerprint"))
                val client = JSch().apply { setHostKeyRepository(pin) }
                var session: Session? = null
                var phase = "ssh_key_failed"
                try {
                    val key = config.getString("privateKey").toByteArray()
                    val pass = config.optString("passphrase").takeIf(String::isNotEmpty)?.toByteArray()
                    try { client.addIdentity("dsh-remote", key, null, pass) } finally { key.fill(0); pass?.fill(0) }
                    phase = "ssh_connect_failed"
                    val next = client.getSession(config.getString("username"), config.getString("host"), config.getInt("sshPort"))
                    session = next
                    next.setConfig("StrictHostKeyChecking", "yes")
                    next.setConfig("PreferredAuthentications", "publickey")
                    next.setServerAliveInterval(15_000)
                    next.setServerAliveCountMax(3)
                    next.connect(10_000)
                    if (stopped) return
                    phase = "forward_rejected"
                    next.setPortForwardingR("127.0.0.1", config.getInt("remotePort"), "127.0.0.1", endpoint.port)
                    connectedBefore = true
                    report("connected")
                    while (next.isConnected && !stopped) Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return
                } catch (_: Exception) {
                    if (stopped) return
                    if (pin.mismatch || phase != "ssh_connect_failed" || !connectedBefore) {
                        report("error", if (pin.mismatch) "fingerprint_mismatch" else phase)
                        return
                    }
                } finally {
                    session?.disconnect()
                    client.removeAllIdentity()
                }
                if (stopped) return
                report("reconnecting")
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return }
            }
        }
    }

    override fun onDestroy() {
        stopped = true
        if (started) unregisterReceiver(screenOff)
        worker.shutdownNow()
        if (launchId == RemoteControlState.launchId && RemoteControlState.state != "error") {
            RemoteControlState.transition("stopped")
        }
        super.onDestroy()
    }

    private class FingerprintPin(private val expected: String) : HostKeyRepository {
        var mismatch = false
            private set
        override fun check(host: String, key: ByteArray): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            val fingerprint = "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
            mismatch = fingerprint != expected
            return if (mismatch) HostKeyRepository.CHANGED else HostKeyRepository.OK
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "dsh-pinned-host"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        const val STOP = "dsh.remote_control.STOP"
        const val LAUNCH = "dsh.remote_control.LAUNCH"
        private const val CHANNEL = "dsh.remote_control"
        private const val NOTIFICATION_ID = 9187
    }
}
