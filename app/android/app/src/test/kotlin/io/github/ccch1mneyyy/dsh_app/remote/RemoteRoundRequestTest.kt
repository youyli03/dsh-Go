package io.github.ccch1mneyyy.dsh_app.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRoundRequestTest {
    @Test fun onlyTheCurrentRequestCanReceiveOneDecision() {
        RemoteControlState.transition("connected")
        val first = RemoteControlState.RoundRequest()
        val next = RemoteControlState.RoundRequest()
        try {
            assertTrue(RemoteControlState.showRoundRequest(first))
            assertFalse(RemoteControlState.showRoundRequest(next))
            assertTrue(RemoteControlState.decideRound(first.id, false))
            assertFalse(first.decision.join())
            assertFalse(RemoteControlState.decideRound(first.id, true))
            RemoteControlState.clearRoundRequest(first)

            assertTrue(RemoteControlState.showRoundRequest(next))
            assertFalse(RemoteControlState.decideRound(first.id, true))
            RemoteControlState.clearRoundRequest(first)
            assertTrue(RemoteControlState.decideRound(next.id, true))
            assertTrue(next.decision.join())
        } finally {
            RemoteControlState.clearRoundRequest(first)
            RemoteControlState.clearRoundRequest(next)
            RemoteControlState.transition("stopped")
        }
    }
}
