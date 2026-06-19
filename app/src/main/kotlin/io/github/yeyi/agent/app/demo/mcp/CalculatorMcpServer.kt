package io.github.yeyi.agent.app.demo.mcp

import io.github.yeyi.agent.mcp.GenericMcpServer
import io.github.yeyi.agent.mcp.InitializeResult
import io.github.yeyi.agent.mcp.JsonRpcNotification
import io.github.yeyi.agent.mcp.ListToolsResult
import io.github.yeyi.agent.mcp.LocalTransport
import io.github.yeyi.agent.mcp.McpServer
import io.github.yeyi.agent.mcp.McpTransport
import io.github.yeyi.agent.mcp.ServerCapabilities
import io.github.yeyi.agent.mcp.ServerInfo
import io.github.yeyi.agent.mcp.ToolsObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A simple local MCP server that provides calculator operations.
 * This demonstrates how to implement and register a local MCP server.
 */
public class CalculatorMcpServer : McpServer {
    override val name: String = "calculator"
    override val description: String = "Simple calculator for basic arithmetic"

    private var initialized = false

    override val transport: McpTransport = LocalTransport.forServer()

    private val tools = listOf(
        buildJsonObject {
            put("name", "add")
            put("description", "Add two numbers")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", "number") })
                    put("b", buildJsonObject { put("type", "number") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
            })
        },
        buildJsonObject {
            put("name", "subtract")
            put("description", "Subtract two numbers")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", "number") })
                    put("b", buildJsonObject { put("type", "number") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
            })
        },
        buildJsonObject {
            put("name", "multiply")
            put("description", "Multiply two numbers")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", "number") })
                    put("b", buildJsonObject { put("type", "number") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
            })
        },
        buildJsonObject {
            put("name", "divide")
            put("description", "Divide two numbers")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", "number") })
                    put("b", buildJsonObject { put("type", "number") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
            })
        }
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
            tools = kotlinx.serialization.json.JsonArray(tools),
            nextCursor = null,
        )
    }

    override suspend fun callTool(params: JsonElement): JsonElement {
        val obj = params.jsonObject
        val name = obj["name"]?.jsonPrimitive?.content
        val args = obj["arguments"]?.jsonObject

        val result = when (name) {
            "add" -> {
                val a = args?.get("a")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val b = args?.get("b")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                a + b
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

    public fun isInitialized() = initialized
}
