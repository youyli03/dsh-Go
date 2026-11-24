package io.github.ccch1mneyyy.dsh_app.remote

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import io.github.ccch1mneyyy.dsh_app.notify.LiveActivity

/** A remote connection is not a grant. Each admitted request has its own cancellation fence. */
internal class RemoteControlPolicy(
    private val activityChanged: (RemoteLiveOperation?) -> Unit = {},
    private val isLocked: () -> Boolean,
) {
    constructor(context: Context) : this({ operation -> LiveActivity.updateRemoteTool(context, operation?.label) }, {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
            context.getSystemService(PowerManager::class.java)?.isInteractive != true
    })
    private val lock = Any()
    private var selected = "confirm"
    private val active = LinkedHashMap<Call, () -> Unit>()

    internal class Call(val operation: String, val phone: Boolean) {
        var liveOperation = RemoteLiveOperation.forMcp(operation)
    }

    fun mode(): String = synchronized(lock) { selected }
    fun activity(): String? = synchronized(lock) { active.keys.firstOrNull()?.operation }
    // Publish under the policy lock to preserve the order of concurrent start and finish events.
    // The observer only stores the display state and queues a refresh.
    private fun publishActivity() {
        activityChanged((active.keys.firstOrNull { it.phone } ?: active.keys.firstOrNull())?.liveOperation)
    }
    fun locked(): Boolean = isLocked()

    fun setMode(mode: String) {
        require(mode == "blocked" || mode == "confirm")
        val cancel = synchronized(lock) {
            if (selected == mode) return
            selected = mode
            removeCalls { it.phone }
        }
        cancel.forEach { it() }
    }

    fun admit(operation: String, phone: Boolean, cancel: () -> Unit): Call? =
        synchronized(lock) {
            if (phone && (locked() || selected == "blocked")) return null
            Call(operation, phone).also {
                active[it] = cancel
                publishActivity()
            }
        }

    fun allowed(call: Call): Boolean = synchronized(lock) {
        call in active && (!call.phone || !locked() && selected == "confirm")
    }

    fun updateLiveOperation(call: Call, operation: RemoteLiveOperation) {
        synchronized(lock) {
            if (call !in active || call.liveOperation == operation) return
            call.liveOperation = operation
            publishActivity()
        }
    }

    fun finish(call: Call) {
        synchronized(lock) {
            if (active.remove(call) != null) publishActivity()
        }
    }

    private fun removeCalls(predicate: (Call) -> Boolean): List<() -> Unit> {
        val removed = active.filterKeys(predicate)
        removed.keys.forEach { active.remove(it) }
        if (removed.isNotEmpty()) publishActivity()
        return removed.values.toList()
    }

    private fun cancelCalls(predicate: (Call) -> Boolean) {
        val cancel = synchronized(lock) { removeCalls(predicate) }
        cancel.forEach { it() }
    }

    fun cancelPhoneCalls() = cancelCalls { it.phone }

    fun revoke() = cancelCalls { true }
}
