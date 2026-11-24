package io.github.ccch1mneyyy.dsh_app.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteControlPolicyTest {
    @Test fun liveOperationFollowsActiveCallsAndCannotBeRestoredAfterCancellation() {
        val policy = RemoteControlPolicy { false }
        val exec = checkNotNull(policy.admit("dsh_exec", false) {})
        assertEquals(RemoteLiveOperation.EXEC, policy.liveOperation())
        policy.updateLiveOperation(exec, RemoteLiveOperation.SHELL)
        assertEquals(RemoteLiveOperation.SHELL, policy.liveOperation())
        val phone = checkNotNull(policy.admit("screen", true) {})
        assertEquals(RemoteLiveOperation.SCREEN, policy.liveOperation())
        policy.finish(phone)
        assertEquals(RemoteLiveOperation.SHELL, policy.liveOperation())
        policy.revoke()
        policy.updateLiveOperation(exec, RemoteLiveOperation.READ)
        assertNull(policy.liveOperation())
    }

    @Test fun lockedPhoneCancelsOnlyPhoneCallsThenRevocationCancelsExec() {
        var locked = false
        var phoneCancels = 0
        var execCancels = 0
        val policy = RemoteControlPolicy { locked }
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

    @Test fun switchingOffRevokesPhoneCallsWithoutRevokingExec() {
        val policy = RemoteControlPolicy { false }
        var cancels = 0
        val call = checkNotNull(policy.admit("tap", true) { cancels++ })
        val exec = checkNotNull(policy.admit("dsh_exec", false) {})
        assertTrue(policy.allowed(call))
        policy.setMode("blocked")
        assertEquals(1, cancels)
        assertNull(policy.admit("screen", true) {})
        assertTrue(policy.allowed(exec))
        policy.setMode("confirm")
        assertFalse(policy.allowed(call))
        assertNotNull(policy.admit("screen", true) {})
    }
}
