package io.github.yeyi.agent.app.demo.mcp

import io.github.yeyi.agent.mcp.CallToolParams
import io.github.yeyi.agent.mcp.InitializeResult
import io.github.yeyi.agent.mcp.ListToolsResult
import io.github.yeyi.agent.mcp.LocalTransport
import io.github.yeyi.agent.mcp.Mcp
import io.github.yeyi.agent.mcp.McpClient
import io.github.yeyi.agent.mcp.McpServer
import io.github.yeyi.agent.mcp.McpTransport
import io.github.yeyi.agent.mcp.ServerCapabilities
import io.github.yeyi.agent.mcp.ServerInfo
import io.github.yeyi.agent.mcp.ToolDef
import io.github.yeyi.agent.mcp.ToolsObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CalculatorMcp : Mcp() {
    override val name: String = "calculator"
    override val description: String = "Calculator服务，支持加、减、乘、除运算"
    override val client: McpClient = McpClient(LocalTransport(CalculatorMcpServer()))
}

/**
 * A simple local MCP server that provides calculator operations.
 * This demonstrates how to implement and register a local MCP server.
 */
private class CalculatorMcpServer : McpServer {
    private val name: String = "calculator"

    private var initialized = false

    override val transport: McpTransport = LocalTransport.forServer()

    private val tools = listOf(
        ToolDef(
            name = "add",
            description = "Add multiple numbers",
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("numbers", buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject { put("type", JsonPrimitive("number")) })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("numbers")) })
            },
        ),
        ToolDef(
            name = "subtract",
            description = "Subtract two numbers",
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("b", buildJsonObject { put("type", JsonPrimitive("number")) })
                })
                put("required", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
            },
        ),
        ToolDef(
            name = "multiply",
            description = "Multiply two numbers",
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("b", buildJsonObject { put("type", JsonPrimitive("number")) })
                })
                put("required", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
            },
        ),
        ToolDef(
            name = "divide",
            description = "Divide two numbers",
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("b", buildJsonObject { put("type", JsonPrimitive("number")) })
                })
                put("required", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
            },
        ),
    )

    override suspend fun initialize(): InitializeResult {
        initialized = true
        return InitializeResult(
            protocolVersion = "2025-06-18",
            serverInfo = ServerInfo(name, "1.0.0"),
            capabilities = ServerCapabilities(tools = ToolsObject(listChanged = false)),
        )
    }

    override suspend fun listTools(cursor: String?): ListToolsResult {
        return ListToolsResult(
            tools = tools,
            nextCursor = null,
        )
    }

    override suspend fun callTool(params: CallToolParams): JsonElement {
        val name = params.name
        val args = params.arguments

        val result = when (name) {
            "add" -> {
                val numbers = args?.get("numbers")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content.toDoubleOrNull() }
                    ?: emptyList()
                numbers.sum()
            }
            "subtract" -> {
                val a = args?.get("a")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val b = args?.get("b")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                a - b
            }
            "multiply" -> {
                val a = args?.get("a")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val b = args?.get("b")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                a * b
            }
            "divide" -> {
                val a = args?.get("a")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val b = args?.get("b")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                if (b == 0.0) return buildJsonObject {
                    put("isError", true)
                    put("content", JsonArray(listOf(
                        buildJsonObject { put("type", "text"); put("text", "Division by zero") }
                    )))
                }
                a / b
            }
            else -> return buildJsonObject {
                put("isError", true)
                put("content", JsonArray(listOf(
                    buildJsonObject { put("type", "text"); put("text", "Unknown tool: $name") }
                )))
            }
        }

        return buildJsonObject {
            put("content", JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", result.toString()) }
            )))
        }
    }

    override suspend fun ping(): Boolean = initialized

    override suspend fun close() {}
}
