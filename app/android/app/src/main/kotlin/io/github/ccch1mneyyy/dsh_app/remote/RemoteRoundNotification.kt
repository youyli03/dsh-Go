package io.github.ccch1mneyyy.dsh_app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R

internal class RemoteRoundNotification(private val context: Context, private val requestId: String) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show() {
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.remote_round_channel), NotificationManager.IMPORTANCE_HIGH,
        ))
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("dshgo://open/remote-control"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.remote_round_title))
            .setContentText(context.getString(R.string.remote_round_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(RemoteControlState.ROUND_TIMEOUT_SECONDS * 1_000)
            .build()
        try {
            manager.notify(requestId, ID, notification)
        } catch (_: SecurityException) {
            // Notifications may be revoked between the check and posting. The page still works.
        }
    }

    fun dismiss() = manager.cancel(requestId, ID)

    private companion object {
        const val CHANNEL = "dsh.remote_control.round"
        const val ID = 9188
    }
}
