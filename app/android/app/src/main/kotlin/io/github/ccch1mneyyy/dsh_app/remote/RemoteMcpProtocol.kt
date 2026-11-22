package io.github.ccch1mneyyy.dsh_app.remote

import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.phone.McpServer
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput

/** Stateless tool-only MCP protocol. Never accepts the private DSH phone attribution envelope. */
internal class RemoteMcpProtocol(
    tools: List<Map<String, Any?>>,
    private val invokeTool: (String, Map<String, Any?>) -> ToolOutput,
) {
    private val names = tools.map { it.getValue("name") as String }.toSet()
    private val server = McpServer(tools, { name, args, _ -> invokeTool(name, args) })

    fun reply(input: String): String? {
        val request = try { Json.parse(input) as? Map<*, *> } catch (_: Json.JsonException) {
            return McpServer.error(null, -32700, "Parse error")
        }
        val id = request?.get("id")
        if (request == null || request["jsonrpc"] != "2.0" || request["method"] !is String)
            return McpServer.error(id, -32600, "Invalid request")
        if (!request.containsKey("id")) return null
        return when (request["method"]) {
            "initialize" -> McpServer.result(id, mapOf(
                "protocolVersion" to ((request["params"] as? Map<*, *>)?.get("protocolVersion")
                    ?.takeIf { it in McpServer.KNOWN_VERSIONS && it != "2024-11-05" } ?: McpServer.LATEST_VERSION),
                "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
                "serverInfo" to mapOf("name" to "dsh-go-remote", "version" to "1"),
            ))
            "ping", "tools/list" -> server.handle(input)
            "tools/call" -> {
                val params = request["params"] as? Map<*, *>
                val name = params?.get("name") as? String
                val args = params?.get("arguments")
                if (name == null || name !in names || args != null && args !is Map<*, *>) {
                    McpServer.error(id, -32602, "Unknown tool or invalid arguments")
                } else {
                    @Suppress("UNCHECKED_CAST")
                    server.runCall(id, name, args as? Map<String, Any?> ?: emptyMap(), null, null)
                }
            }
            else -> McpServer.error(id, -32601, "Method not found")
        }
    }
}
