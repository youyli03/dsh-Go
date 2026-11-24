package io.github.ccch1mneyyy.dsh_app.remote

import com.jcraft.jsch.JSchException
import java.net.SocketException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlRetryTest {
    @Test fun transportFailureRetriesEvenBeforeTheFirstConnection() {
        assertTrue(RemoteControlService.shouldRetry(
            "ssh_connect_failed", false, JSchException("Connection", UnknownHostException()),
        ))
        assertTrue(RemoteControlService.shouldRetry("ssh_connect_failed", true, SocketException()))
    }

    @Test fun signInErrorsStopAndPreviouslyWorkingForwardCanRecover() {
        assertFalse(RemoteControlService.shouldRetry("ssh_key_failed", true, JSchException("Invalid key")))
        assertFalse(RemoteControlService.shouldRetry("ssh_connect_failed", true, JSchException("Auth fail")))
        val occupied = JSchException("Remote port in use")
        assertFalse(RemoteControlService.shouldRetry("forward_rejected", false, occupied))
        assertTrue(RemoteControlService.shouldRetry("forward_rejected", true, occupied))
    }
}
