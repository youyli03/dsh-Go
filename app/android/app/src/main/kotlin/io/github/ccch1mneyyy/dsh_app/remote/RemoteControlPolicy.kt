package io.github.ccch1mneyyy.dsh_app.remote

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.os.PowerManager

/** A remote connection is not a grant. Each admitted request has its own cancellation fence. */
internal class RemoteControlPolicy(private val isLocked: () -> Boolean, private val elapsed: () -> Long) {
    constructor(context: Context) : this({
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
            context.getSystemService(PowerManager::class.java)?.isInteractive != true
    }, SystemClock::elapsedRealtime)
    private val lock = Any()
    private var selected = "confirm"
    private var timedUntil = 0L
    private var previous = "confirm"
    private val active = LinkedHashMap<Call, () -> Unit>()

    internal class Call(val operation: String, val phone: Boolean, val mode: String)

    fun mode(): String = synchronized(lock) { currentMode() }
    fun activity(): String? = synchronized(lock) { active.keys.firstOrNull()?.operation }
    fun locked(): Boolean = isLocked()

    private fun currentMode(): String {
        if (selected == "timed" && elapsed() >= timedUntil) selected = previous
        return selected
    }

    fun setMode(mode: String) {
        require(mode in setOf("blocked", "confirm", "timed"))
        val cancel = synchronized(lock) {
            val old = currentMode()
            if (old == mode) return
            if (mode == "timed") {
                previous = old
                timedUntil = elapsed() + 5 * 60_000L
            }
            selected = mode
            removeCalls { it.phone }
        }
        cancel.forEach { it() }
    }

    fun admit(operation: String, phone: Boolean, cancel: () -> Unit): Call? = synchronized(lock) {
        val mode = currentMode()
        if (phone && (locked() || mode == "blocked")) return null
        Call(operation, phone, mode).also { active[it] = cancel }
    }

    fun allowed(call: Call): Boolean = synchronized(lock) {
        call in active && (!call.phone || !locked() && currentMode() == call.mode && call.mode != "blocked")
    }

    fun finish(call: Call) { synchronized(lock) { active.remove(call) } }

    private fun removeCalls(predicate: (Call) -> Boolean): List<() -> Unit> {
        val removed = active.filterKeys(predicate)
        removed.keys.forEach { active.remove(it) }
        return removed.values.toList()
    }

    fun cancelPhoneCalls() = synchronized(lock) { removeCalls { it.phone } }.forEach { it() }

    fun revoke() {
        val cancel = synchronized(lock) {
            if (selected == "timed") selected = previous
            removeCalls { true }
        }
        cancel.forEach { it() }
    }
}
