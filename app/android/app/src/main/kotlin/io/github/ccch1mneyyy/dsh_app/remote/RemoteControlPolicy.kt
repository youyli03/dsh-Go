package io.github.ccch1mneyyy.dsh_app.remote

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/** A remote connection is not a grant. Each admitted request has its own cancellation fence. */
internal class RemoteControlPolicy(private val isLocked: () -> Boolean) {
    constructor(context: Context) : this({
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
            context.getSystemService(PowerManager::class.java)?.isInteractive != true
    })
    private val lock = Any()
    private var selected = "confirm"
    private val active = LinkedHashMap<Call, () -> Unit>()

    internal class Call(val operation: String, val phone: Boolean)

    fun mode(): String = synchronized(lock) { selected }
    fun activity(): String? = synchronized(lock) { active.keys.firstOrNull()?.operation }
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

    fun admit(operation: String, phone: Boolean, cancel: () -> Unit): Call? = synchronized(lock) {
        if (phone && (locked() || selected == "blocked")) return null
        Call(operation, phone).also { active[it] = cancel }
    }

    fun allowed(call: Call): Boolean = synchronized(lock) {
        call in active && (!call.phone || !locked() && selected == "confirm")
    }

    fun finish(call: Call) { synchronized(lock) { active.remove(call) } }

    private fun removeCalls(predicate: (Call) -> Boolean): List<() -> Unit> {
        val removed = active.filterKeys(predicate)
        removed.keys.forEach { active.remove(it) }
        return removed.values.toList()
    }

    fun cancelPhoneCalls() = synchronized(lock) { removeCalls { it.phone } }.forEach { it() }

    fun revoke() = synchronized(lock) { removeCalls { true } }.forEach { it() }
}
