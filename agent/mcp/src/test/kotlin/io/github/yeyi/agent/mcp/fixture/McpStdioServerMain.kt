@file:JvmName("McpStdioServerMain")

package io.github.yeyi.agent.mcp.fixture

import io.github.yeyi.agent.mcp.McpServer
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val serverClassName = args.getOrNull(0)
        ?: error("Usage: McpStdioServerMain <McpServer-class-name>")
    val server = Class.forName(serverClassName)
        .getDeclaredConstructor()
        .newInstance() as McpServer
    runBlocking { (server.transport as StdioServerTransport).run() }
}
