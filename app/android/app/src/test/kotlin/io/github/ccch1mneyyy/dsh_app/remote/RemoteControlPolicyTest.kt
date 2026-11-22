package io.github.ccch1mneyyy.dsh_app.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteControlPolicyTest {
    @Test fun lockedPhoneCancelsOnlyPhoneCallsThenRevocationCancelsExec() {
        var locked = false
        var phoneCancels = 0
        var execCancels = 0
        val policy = RemoteControlPolicy({ locked }, { 0L })
        val phone = checkNotNull(policy.admit("tap", true) { phoneCancels++ })
        val exec = checkNotNull(policy.admit("dsh_exec", false) { execCancels++ })
        locked = true
        assertFalse(policy.allowed(phone))
        assertNull(policy.admit("screen", true) {})
        policy.cancelPhoneCalls()
        locked = false
        assertFalse(policy.allowed(phone))
        assertTrue(policy.allowed(exec))
        assertEquals(1, phoneCancels)
        policy.revoke()
        assertFalse(policy.allowed(exec))
        assertEquals(1, execCancels)
    }

    @Test fun expiredTemporaryGrantCannotAuthorizeInFlightCall() {
        var now = 0L
        val policy = RemoteControlPolicy({ false }, { now })
        policy.setMode("timed")
        val call = checkNotNull(policy.admit("tap", true) {})
        assertTrue(policy.allowed(call))
        now = 300_000L
        assertFalse(policy.allowed(call))
        assertEquals("confirm", policy.mode())
    }
}
