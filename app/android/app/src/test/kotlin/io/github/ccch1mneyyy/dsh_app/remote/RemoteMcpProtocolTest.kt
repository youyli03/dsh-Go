package io.github.ccch1mneyyy.dsh_app.remote

import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMcpProtocolTest {
    @Test fun externalWireCannotUsePrivateMethodsOrDispatchUnlistedTools() {
        val called = mutableListOf<String>()
        val protocol = RemoteMcpProtocol(listOf(mapOf("name" to "screen"))) { name, _ ->
            called += name
            ToolOutput("ok")
        }
        fun send(method: String, params: Map<String, Any?> = emptyMap()): Map<*, *> =
            Json.parse(checkNotNull(protocol.reply(Json.write(mapOf("jsonrpc" to "2.0", "id" to 1,
                "method" to method, "params" to params))))) as Map<*, *>

        assertEquals(-32601L, (send("dsh-go/phone-run-end")["error"] as Map<*, *>)["code"])
        assertEquals(-32602L, (send("tools/call", mapOf("name" to "private"))["error"] as Map<*, *>)["code"])
        assertEquals(-32602L, (send("tools/call", mapOf("name" to "screen", "arguments" to "bad"))["error"] as Map<*, *>)["code"])
        assertEquals(emptyList<String>(), called)
        assertEquals(false, (send("tools/call", mapOf("name" to "screen", "_meta" to mapOf(
            "dsh-go/phone-caller" to mapOf("sessionId" to "spoofed"))))["result"] as Map<*, *>)["isError"])
        assertEquals(listOf("screen"), called)
        assertNull(protocol.reply("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }
}
