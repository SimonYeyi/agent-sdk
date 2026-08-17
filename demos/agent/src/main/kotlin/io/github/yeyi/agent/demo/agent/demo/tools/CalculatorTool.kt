package io.github.yeyi.agent.demo.agent.demo.tools

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 教学 Tool: 多参数 + JSON schema 表达 + 错误处理。
 * 支持 + - * / 四则运算,纯字符串 parser(避免 javax.script 依赖以保证 Android 兼容)。
 */
class CalculatorTool : Tool {
    override val name = "calculator"
    override val description = "执行四则运算表达式(支持 + - * / 和括号)"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
          "type": "object",
          "properties": {
            "expression": {
              "type": "string",
              "description": "如 '(3+5)*7'"
            }
          },
          "required": ["expression"]
        }
        """.trimIndent()
    )

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val expr = (arguments as JsonObject)["expression"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("ERROR: missing 'expression' field")
        return try {
            val result = evaluate(expr)
            ToolExecutionResult.success("$expr = $result")
        } catch (e: Throwable) {
            ToolExecutionResult.error("ERROR: ${e.message}")
        }
    }

    /** 极简表达式求值(支持 + - * / 和括号,整数运算)。
     *  不追求完美,只满足 demo 教学。 */
    private fun evaluate(expr: String): Long {
        val tokens = expr.replace(" ", "").toCharArray()
        val stack = ArrayDeque<Long>()
        val ops = ArrayDeque<Char>()
        var i = 0
        while (i < tokens.size) {
            val c = tokens[i]
            when {
                c.isDigit() -> {
                    var n = 0L
                    while (i < tokens.size && tokens[i].isDigit()) {
                        n = n * 10 + (tokens[i] - '0')
                        i++
                    }
                    stack.addLast(n)
                    continue
                }
                c == '(' -> ops.addLast(c)
                c == ')' -> {
                    while (ops.last() != '(') applyOp(stack, ops.removeLast())
                    ops.removeLast()
                }
                c in "+-*/" -> {
                    while (ops.isNotEmpty() && ops.last() != '(' && precedence(ops.last()) >= precedence(c)) {
                        applyOp(stack, ops.removeLast())
                    }
                    ops.addLast(c)
                }
            }
            i++
        }
        while (ops.isNotEmpty()) applyOp(stack, ops.removeLast())
        return stack.last()
    }

    private fun precedence(op: Char) = when (op) { '+', '-' -> 1; '*', '/' -> 2; else -> 0 }

    private fun applyOp(stack: ArrayDeque<Long>, op: Char) {
        val b = stack.removeLast()
        val a = stack.removeLast()
        stack.addLast(when (op) {
            '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> a / b; else -> error("unknown op $op")
        })
    }
}
