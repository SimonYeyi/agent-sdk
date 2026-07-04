package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionParserTest {

    private val parser = DefaultExecutionParser()

    @Test
    fun parseSimpleExecution() {
        val signature = FunctionSignature(
            name = "send_email",
            params = listOf(
                Param("to", ParamType.StringType(), required = true),
                Param("subject", ParamType.StringType(), required = true)
            )
        )

        val result = parser.parse("send_email(to='x@x.com', subject='hello')", signature)

        assertEquals("x@x.com", result.jsonObject["to"]?.jsonPrimitive?.content)
        assertEquals("hello", result.jsonObject["subject"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithArray() {
        val signature = FunctionSignature(
            name = "send_email",
            params = listOf(
                Param("to", ParamType.StringType(), required = true),
                Param("tags", ParamType.StringType(isArray = true), required = false)
            )
        )

        val result = parser.parse("send_email(to='x@x.com', tags=['work', 'urgent'])", signature)

        assertEquals("x@x.com", result.jsonObject["to"]?.jsonPrimitive?.content)
        val tags = result.jsonObject["tags"]?.jsonArray
        assertEquals(2, tags?.jsonArray?.size)
        assertEquals("work", tags?.jsonArray?.get(0)?.jsonPrimitive?.content)
        assertEquals("urgent", tags?.jsonArray?.get(1)?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithEnum() {
        val signature = FunctionSignature(
            name = "update_status",
            params = listOf(
                Param("id", ParamType.StringType(), required = true),
                Param("status", ParamType.EnumType(listOf("todo", "in_progress", "done")), required = true)
            )
        )

        val result = parser.parse("update_status(id='123', status=in_progress)", signature)

        assertEquals("123", result.jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("in_progress", result.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithNumber() {
        val signature = FunctionSignature(
            name = "create_user",
            params = listOf(
                Param("name", ParamType.StringType(), required = true),
                Param("age", ParamType.NumberType(), required = false)
            )
        )

        val result = parser.parse("create_user(name='John', age=30)", signature)

        assertEquals("John", result.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, result.jsonObject["age"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun parseExecutionWithBoolean() {
        val signature = FunctionSignature(
            name = "set_flag",
            params = listOf(
                Param("enabled", ParamType.BooleanType(), required = true)
            )
        )

        val resultTrue = parser.parse("set_flag(enabled=true)", signature)
        assertTrue(resultTrue.jsonObject["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true)

        val resultFalse = parser.parse("set_flag(enabled=false)", signature)
        assertTrue(resultFalse.jsonObject["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false)
    }

    @Test
    fun parseExecutionWithOptionalParams() {
        val signature = FunctionSignature(
            name = "send_email",
            params = listOf(
                Param("to", ParamType.StringType(), required = true),
                Param("cc", ParamType.StringType(isArray = true), required = false)
            )
        )

        val result = parser.parse("send_email(to='x@x.com')", signature)

        assertEquals("x@x.com", result.jsonObject["to"]?.jsonPrimitive?.content)
        assertNull(result.jsonObject["cc"])
    }

    @Test
    fun parseExecutionWithEscapedQuote() {
        val signature = FunctionSignature(
            name = "send_message",
            params = listOf(
                Param("message", ParamType.StringType(), required = true)
            )
        )

        val result = parser.parse("send_message(message='it\\'s great')", signature)

        assertEquals("it's great", result.jsonObject["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseEmptyExecution() {
        val signature = FunctionSignature(
            name = "noop",
            params = emptyList()
        )

        val result = parser.parse("noop()", signature)

        assertTrue(result.jsonObject.isEmpty())
    }

    @Test
    fun parseEnumWithQuotes() {
        val signature = FunctionSignature(
            name = "update_status",
            params = listOf(
                Param("status", ParamType.EnumType(listOf("todo", "in_progress", "done")), required = true)
            )
        )

        // 枚举值带双引号
        val result = parser.parse("update_status(status=\"in_progress\")", signature)
        assertEquals("in_progress", result.jsonObject["status"]?.jsonPrimitive?.content)

        // 枚举值带单引号
        val result2 = parser.parse("update_status(status='done')", signature)
        assertEquals("done", result2.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseNumberWithQuotes() {
        val signature = FunctionSignature(
            name = "create_user",
            params = listOf(
                Param("age", ParamType.NumberType(), required = true)
            )
        )

        // 数字带双引号应仍能正确解析
        val result = parser.parse("create_user(age=\"25\")", signature)
        assertEquals(25.0, result.jsonObject["age"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun parseBooleanWithQuotes() {
        val signature = FunctionSignature(
            name = "set_flag",
            params = listOf(
                Param("enabled", ParamType.BooleanType(), required = true)
            )
        )

        // 布尔带双引号应仍能正确解析
        val resultTrue = parser.parse("set_flag(enabled=\"true\")", signature)
        assertTrue(resultTrue.jsonObject["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true)

        val resultFalse = parser.parse("set_flag(enabled=\"false\")", signature)
        assertTrue(resultFalse.jsonObject["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false)
    }

    @Test
    fun parseExecutionWithObjectType() {
        // 对象类型解析暂不支持复杂场景，简化测试
        val signature = FunctionSignature(
            name = "create_config",
            params = listOf(
                Param("config", ParamType.ObjectType(), required = true)
            )
        )

        val result = parser.parse("create_config(config={timeout: 30})", signature)

        // 对象类型返回原始字符串，暂不验证内部结构
        assertTrue(result.jsonObject["config"] != null)
    }

    @Test
    fun parseMusicControlPlayAction() {
        // 播放歌曲：action=play + song + 可选的 artist
        val signature = FunctionSignature(
            name = "music_control",
            params = listOf(
                Param("action", ParamType.EnumType(listOf("play", "pause", "stop", "prev", "next", "volume", "mode")), required = true),
                Param("song", ParamType.StringType(), required = false),
                Param("artist", ParamType.StringType(), required = false),
                Param("volume", ParamType.NumberType(), required = false),
                Param("mode", ParamType.EnumType(listOf("normal", "repeat", "shuffle")), required = false)
            )
        )

        val result = parser.parse("music_control(action=play, song='海阔天空', artist='Beyond')", signature)

        assertEquals("play", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("海阔天空", result.jsonObject["song"]?.jsonPrimitive?.content)
        assertEquals("Beyond", result.jsonObject["artist"]?.jsonPrimitive?.content)
        assertNull(result.jsonObject["volume"])
        assertNull(result.jsonObject["mode"])
    }

    @Test
    fun parseMusicControlVolumeAction() {
        // 调节音量：action=volume + volume 参数
        val signature = FunctionSignature(
            name = "music_control",
            params = listOf(
                Param("action", ParamType.EnumType(listOf("play", "pause", "stop", "prev", "next", "volume", "mode")), required = true),
                Param("song", ParamType.StringType(), required = false),
                Param("artist", ParamType.StringType(), required = false),
                Param("volume", ParamType.NumberType(), required = false),
                Param("mode", ParamType.EnumType(listOf("normal", "repeat", "shuffle")), required = false)
            )
        )

        val result = parser.parse("music_control(action=volume, volume=75)", signature)

        assertEquals("volume", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals(75.0, result.jsonObject["volume"]?.jsonPrimitive?.content?.toDouble())
        assertNull(result.jsonObject["song"])
    }

    @Test
    fun parseMusicControlModeAction() {
        // 切换模式：action=mode + mode 参数
        val signature = FunctionSignature(
            name = "music_control",
            params = listOf(
                Param("action", ParamType.EnumType(listOf("play", "pause", "stop", "prev", "next", "volume", "mode")), required = true),
                Param("song", ParamType.StringType(), required = false),
                Param("artist", ParamType.StringType(), required = false),
                Param("volume", ParamType.NumberType(), required = false),
                Param("mode", ParamType.EnumType(listOf("normal", "repeat", "shuffle")), required = false)
            )
        )

        val result = parser.parse("music_control(action=mode, mode=shuffle)", signature)

        assertEquals("mode", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("shuffle", result.jsonObject["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseMusicControlSimpleAction() {
        // 简单操作：action=pause/stop/prev/next 不需要额外参数
        val signature = FunctionSignature(
            name = "music_control",
            params = listOf(
                Param("action", ParamType.EnumType(listOf("play", "pause", "stop", "prev", "next", "volume", "mode")), required = true),
                Param("song", ParamType.StringType(), required = false),
                Param("artist", ParamType.StringType(), required = false),
                Param("volume", ParamType.NumberType(), required = false),
                Param("mode", ParamType.EnumType(listOf("normal", "repeat", "shuffle")), required = false)
            )
        )

        val result = parser.parse("music_control(action=pause)", signature)

        assertEquals("pause", result.jsonObject["action"]?.jsonPrimitive?.content)
        // 只有 action 字段有值，其他字段不存在于 result 中
        assertNull(result.jsonObject["song"])
        assertNull(result.jsonObject["artist"])
        assertNull(result.jsonObject["volume"])
        assertNull(result.jsonObject["mode"])
    }
}
