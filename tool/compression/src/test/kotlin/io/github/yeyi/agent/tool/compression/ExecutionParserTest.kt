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

    // region 公共 Fixture —— 3 个 signature 覆盖多组测试场景

    /** music_control 普通模式：5 个 params（action 枚举 + song/artist/volume/mode 可选） */
    private val musicControlSignature = FunctionSignature(
        name = "music_control",
        params = listOf(
            Param("action", ParamType.EnumType(listOf("play", "pause", "stop", "prev", "next", "volume", "mode")), required = true),
            Param("song", ParamType.StringType(), required = false),
            Param("artist", ParamType.StringType(), required = false),
            Param("volume", ParamType.NumberType(), required = false),
            Param("mode", ParamType.EnumType(listOf("normal", "repeat", "shuffle")), required = false)
        )
    )

    /** music_control oneOf 模式：3 个 branches（play 带 song+artist / pause 空 / volume 带 volume） */
    private val musicControlOneOfSignature = FunctionSignature(
        name = "music_control",
        params = emptyList(),
        branches = listOf(
            Branch("action=play", listOf(
                Param("song", ParamType.StringType(), required = true),
                Param("artist", ParamType.StringType(), required = false)
            )),
            Branch("action=pause", emptyList()),
            Branch("action=volume", listOf(
                Param("volume", ParamType.NumberType(), required = true)
            ))
        )
    )

    /** get_weather 基础 signature：city 必填 + time 可选（string） */
    private val getWeatherSignature = FunctionSignature(
        name = "get_weather",
        params = listOf(
            Param("city", ParamType.StringType(), required = true),
            Param("time", ParamType.StringType(), required = false)
        )
    )

    // endregion

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
        // 不透明 object(没有 fields):内层用 key=value 语法,未知字段退化为 StringType
        val signature = FunctionSignature(
            name = "create_config",
            params = listOf(
                Param("config", ParamType.ObjectType(), required = true)
            )
        )

        val result = parser.parse("create_config(config={timeout=30, name='hello'})", signature)
        val config = result.jsonObject["config"]!!.jsonObject
        assertEquals("30", config["timeout"]?.jsonPrimitive?.content)
        assertEquals("hello", config["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithStructuredObject() {
        // 带 fields 的 object:解析时按 schema 给字段分派类型
        val signature = FunctionSignature(
            name = "create_user",
            params = listOf(
                Param("user", ParamType.ObjectType(fields = listOf(
                    Param("name", ParamType.StringType(), required = true),
                    Param("age", ParamType.NumberType(), required = false),
                    Param("active", ParamType.BooleanType(), required = false),
                )), required = true)
            )
        )

        val result = parser.parse("create_user(user={name='Alice', age=30, active=true})", signature)
        val user = result.jsonObject["user"]!!.jsonObject
        assertEquals("Alice", user["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, user["age"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(true, user["active"]?.jsonPrimitive?.content?.toBooleanStrict())
    }

    @Test
    fun parseExecutionWithArrayOfObjects() {
        // 数组元素是结构化对象:每个元素按 schema 解析
        val signature = FunctionSignature(
            name = "create_users",
            params = listOf(
                Param("users", ParamType.ObjectType(isArray = true, fields = listOf(
                    Param("name", ParamType.StringType(), required = true),
                    Param("age", ParamType.NumberType(), required = false),
                )), required = true)
            )
        )

        val result = parser.parse("create_users(users=[{name='Alice', age=30}, {name='Bob'}])", signature)
        val users = result.jsonObject["users"]!!.jsonArray
        assertEquals(2, users.size)
        assertEquals("Alice", users[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, users[0].jsonObject["age"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("Bob", users[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertNull(users[1].jsonObject["age"])
    }

    @Test
    fun parseExecutionWithDeepNesting() {
        // 深度嵌套:object → array of object → object
        val signature = FunctionSignature(
            name = "create_orders",
            params = listOf(
                Param("orders", ParamType.ObjectType(fields = listOf(
                    Param("order_id", ParamType.StringType(), required = true),
                    Param("items", ParamType.ObjectType(isArray = true, fields = listOf(
                        Param("sku", ParamType.StringType(), required = true),
                        Param("qty", ParamType.NumberType(), required = true),
                    )), required = true),
                )), required = true)
            )
        )

        val result = parser.parse(
            "create_orders(orders={order_id='A1', items=[{sku='X', qty=2}, {sku='Y', qty=1}]})",
            signature
        )
        val orders = result.jsonObject["orders"]!!.jsonObject
        assertEquals("A1", orders["order_id"]?.jsonPrimitive?.content)
        val items = orders["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals("X", items[0].jsonObject["sku"]?.jsonPrimitive?.content)
        assertEquals(2.0, items[0].jsonObject["qty"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("Y", items[1].jsonObject["sku"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithEmptyObject() {
        val signature = FunctionSignature(
            name = "noop",
            params = listOf(
                Param("meta", ParamType.ObjectType(), required = false)
            )
        )

        val result = parser.parse("noop(meta={ })", signature)
        val meta = result.jsonObject["meta"]!!.jsonObject
        assertTrue(meta.isEmpty())
    }

    @Test
    fun parseMusicControlPlayAction() {
        // 播放歌曲：action=play + song + 可选的 artist
        val result = parser.parse("music_control(action=play, song='海阔天空', artist='Beyond')", musicControlSignature)

        assertEquals("play", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("海阔天空", result.jsonObject["song"]?.jsonPrimitive?.content)
        assertEquals("Beyond", result.jsonObject["artist"]?.jsonPrimitive?.content)
        assertNull(result.jsonObject["volume"])
        assertNull(result.jsonObject["mode"])
    }

    @Test
    fun parseMusicControlVolumeAction() {
        // 调节音量：action=volume + volume 参数
        val result = parser.parse("music_control(action=volume, volume=75)", musicControlSignature)

        assertEquals("volume", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals(75.0, result.jsonObject["volume"]?.jsonPrimitive?.content?.toDouble())
        assertNull(result.jsonObject["song"])
    }

    @Test
    fun parseMusicControlModeAction() {
        // 切换模式：action=mode + mode 参数
        val result = parser.parse("music_control(action=mode, mode=shuffle)", musicControlSignature)

        assertEquals("mode", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("shuffle", result.jsonObject["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseMusicControlSimpleAction() {
        // 简单操作：action=pause/stop/prev/next 不需要额外参数
        val result = parser.parse("music_control(action=pause)", musicControlSignature)

        assertEquals("pause", result.jsonObject["action"]?.jsonPrimitive?.content)
        // 只有 action 字段有值，其他字段不存在于 result 中
        assertNull(result.jsonObject["song"])
        assertNull(result.jsonObject["artist"])
        assertNull(result.jsonObject["volume"])
        assertNull(result.jsonObject["mode"])
    }

    @Test
    fun parseOneOfExecutionPlay() {
        val result = parser.parse("music_control(action=play, song='海阔天空', artist='Beyond')", musicControlOneOfSignature)

        assertEquals("play", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("海阔天空", result.jsonObject["song"]?.jsonPrimitive?.content)
        assertEquals("Beyond", result.jsonObject["artist"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseOneOfExecutionPause() {
        val result = parser.parse("music_control(action=pause)", musicControlOneOfSignature)

        assertEquals("pause", result.jsonObject["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseOneOfExecutionVolume() {
        val result = parser.parse("music_control(action=volume, volume=75)", musicControlOneOfSignature)

        assertEquals("volume", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals(75.0, result.jsonObject["volume"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun parseOneOfNestedInsideObject() {
        // 顶层是普通 params,channel 字段是 OneOfType,需要按 channel.type 分派子字段
        val signature = FunctionSignature(
            name = "send",
            params = listOf(
                Param("channel", ParamType.OneOfType(branches = listOf(
                    Branch("type=email", listOf(
                        Param("to", ParamType.StringType(), required = true)
                    )),
                    Branch("type=webhook", listOf(
                        Param("url", ParamType.StringType(), required = true)
                    ))
                )), required = true)
            )
        )

        val email = parser.parse("send(channel={type=email, to='a@b.com'})", signature)
        val channel1 = email.jsonObject["channel"]!!.jsonObject
        assertEquals("email", channel1["type"]?.jsonPrimitive?.content)
        assertEquals("a@b.com", channel1["to"]?.jsonPrimitive?.content)

        val webhook = parser.parse("send(channel={type=webhook, url='https://x'})", signature)
        val channel2 = webhook.jsonObject["channel"]!!.jsonObject
        assertEquals("webhook", channel2["type"]?.jsonPrimitive?.content)
        assertEquals("https://x", channel2["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseOneOfArrayElement() {
        // 数组元素是 OneOfType,每个元素按各自的 discriminator 分派
        val signature = FunctionSignature(
            name = "log",
            params = listOf(
                Param("events", ParamType.OneOfType(isArray = true, branches = listOf(
                    Branch("type=click", listOf(
                        Param("x", ParamType.NumberType(), required = true),
                        Param("y", ParamType.NumberType(), required = false)
                    )),
                    Branch("type=view", listOf(
                        Param("page", ParamType.StringType(), required = true)
                    ))
                )), required = true)
            )
        )

        val result = parser.parse("log(events=[{type=click, x=10, y=20}, {type=view, page='/home'}])", signature)
        val events = result.jsonObject["events"]!!.jsonArray
        assertEquals(2, events.size)

        val click = events[0].jsonObject
        assertEquals("click", click["type"]?.jsonPrimitive?.content)
        assertEquals(10.0, click["x"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(20.0, click["y"]?.jsonPrimitive?.content?.toDouble())

        val view = events[1].jsonObject
        assertEquals("view", view["type"]?.jsonPrimitive?.content)
        assertEquals("/home", view["page"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseOneOfFallsBackToCatchAllBranch() {
        // 判别字段值没匹配到任何 condition 时,回退到 catch-all(空 condition)
        val signature = FunctionSignature(
            name = "event",
            params = emptyList(),
            branches = listOf(
                Branch("type=click", listOf(
                    Param("x", ParamType.NumberType(), required = false)
                )),
                Branch("", listOf( // catch-all
                    Param("payload", ParamType.StringType(), required = false)
                ))
            )
        )

        // 走 click 分支
        val click = parser.parse("event(type=click, x=5)", signature)
        assertEquals("click", click.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(5.0, click.jsonObject["x"]?.jsonPrimitive?.content?.toDouble())

        // 走 catch-all 分支(未知 type)
        val unknown = parser.parse("event(type=unknown, payload='raw data')", signature)
        assertEquals("unknown", unknown.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("raw data", unknown.jsonObject["payload"]?.jsonPrimitive?.content)
    }

    // --- 宽容解析:模型返回的非标准格式 ---

    @Test
    fun parseExecutionWithoutFunctionName() {
        // 模型有时只回参数,不带 funcName( 包裹)
        val result = parser.parse("city=\"北京\", time=today", getWeatherSignature)

        assertEquals("北京", result.jsonObject["city"]?.jsonPrimitive?.content)
        assertEquals("today", result.jsonObject["time"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithoutFunctionNameNoQuotes() {
        val result = parser.parse("city=北京", getWeatherSignature)

        assertEquals("北京", result.jsonObject["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithColonSeparator() {
        // a(b : "c") — : 当作 = 的替代
        val result = parser.parse("get_weather(city : \"北京\", time : today)", getWeatherSignature)

        assertEquals("北京", result.jsonObject["city"]?.jsonPrimitive?.content)
        assertEquals("today", result.jsonObject["time"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionWithMixedSeparators() {
        // 混用 = 和 :,宽容处理
        val result = parser.parse("get_weather(city=\"北京\" , time : today)", getWeatherSignature)

        assertEquals("北京", result.jsonObject["city"]?.jsonPrimitive?.content)
        assertEquals("today", result.jsonObject["time"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseNestedObjectWithColonSeparator() {
        // 嵌套 object 里也容忍 : 分隔
        val signature = FunctionSignature(
            name = "create_user",
            params = listOf(
                Param("user", ParamType.ObjectType(fields = listOf(
                    Param("name", ParamType.StringType(), required = true),
                    Param("age", ParamType.NumberType(), required = false),
                )), required = true)
            )
        )

        val result = parser.parse("create_user(user={name : \"Alice\", age : 30})", signature)
        val user = result.jsonObject["user"]!!.jsonObject
        assertEquals("Alice", user["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, user["age"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun parseOneOfWithColonSeparator() {
        // 嵌套 oneOf 里也容忍 : 分隔(包括判别字段)
        val result = parser.parse("music_control(action : play, song : \"海阔天空\")", musicControlOneOfSignature)

        assertEquals("play", result.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("海阔天空", result.jsonObject["song"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseRealisticLLMDriftGetWeather() {
        // 模拟 get_weather 工具常见的模型漂移
        val signature = FunctionSignature(
            name = "get_weather",
            params = listOf(
                Param("city", ParamType.StringType(), required = true),
                Param("time", ParamType.EnumType(listOf("now", "today", "tomorrow", "day_after_tomorrow")), required = false)
            )
        )

        // 形式 1:标准
        val r1 = parser.parse("get_weather(city=\"北京\", time=tomorrow)", signature)
        assertEquals("北京", r1.jsonObject["city"]?.jsonPrimitive?.content)
        assertEquals("tomorrow", r1.jsonObject["time"]?.jsonPrimitive?.content)

        // 形式 2:无函数名
        val r2 = parser.parse("city=\"上海\", time=today", signature)
        assertEquals("上海", r2.jsonObject["city"]?.jsonPrimitive?.content)

        // 形式 3:冒号分隔
        val r3 = parser.parse("get_weather(city: \"广州\", time: now)", signature)
        assertEquals("广州", r3.jsonObject["city"]?.jsonPrimitive?.content)
        assertEquals("now", r3.jsonObject["time"]?.jsonPrimitive?.content)

        // 形式 4:无函数名 + 冒号
        val r4 = parser.parse("city: \"深圳\"", signature)
        assertEquals("深圳", r4.jsonObject["city"]?.jsonPrimitive?.content)
    }

    // --- 宽容解析:Kotlin-style 位置参数(无参数名,按顺序赋值) ---

    @Test
    fun parseExecutionPositionalStrings() {
        val signature = FunctionSignature(
            name = "send_email",
            params = listOf(
                Param("to", ParamType.StringType(), required = true),
                Param("subject", ParamType.StringType(), required = true)
            )
        )

        // 顶层位置参数:按 params 顺序映射
        val result = parser.parse("send_email(\"x@x.com\", \"hello\")", signature)

        assertEquals("x@x.com", result.jsonObject["to"]?.jsonPrimitive?.content)
        assertEquals("hello", result.jsonObject["subject"]?.jsonPrimitive?.content)
    }

    @Test
    fun parseExecutionPositionalMixedTypes() {
        val signature = FunctionSignature(
            name = "set_config",
            params = listOf(
                Param("name", ParamType.StringType(), required = true),
                Param("count", ParamType.NumberType(), required = true),
                Param("enabled", ParamType.BooleanType(), required = true)
            )
        )

        val result = parser.parse("set_config(\"name\", 30, true)", signature)

        assertEquals("name", result.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, result.jsonObject["count"]?.jsonPrimitive?.content?.toDouble())
        assertTrue(result.jsonObject["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true)
    }

    @Test
    fun parseExecutionPositionalExtraArgs() {
        val signature = FunctionSignature(
            name = "send_email",
            params = listOf(
                Param("to", ParamType.StringType(), required = true),
                Param("subject", ParamType.StringType(), required = true)
            )
        )

        // 3 个实参但只 2 个 param:多余的静默丢弃
        val result = parser.parse("send_email(\"x@x.com\", \"hello\", \"extra\")", signature)

        assertEquals("x@x.com", result.jsonObject["to"]?.jsonPrimitive?.content)
        assertEquals("hello", result.jsonObject["subject"]?.jsonPrimitive?.content)
        assertEquals(2, result.jsonObject.size)
    }

    @Test
    fun parseExecutionPositionalWithArrayValue() {
        val signature = FunctionSignature(
            name = "send_email",
            params = listOf(
                Param("to", ParamType.StringType(), required = true),
                Param("tags", ParamType.StringType(isArray = true), required = true)
            )
        )

        val result = parser.parse("send_email(\"x@x.com\", [\"work\", \"urgent\"])", signature)

        assertEquals("x@x.com", result.jsonObject["to"]?.jsonPrimitive?.content)
        val tags = result.jsonObject["tags"]?.jsonArray
        assertEquals(2, tags?.size)
        assertEquals("work", tags?.get(0)?.jsonPrimitive?.content)
        assertEquals("urgent", tags?.get(1)?.jsonPrimitive?.content)
    }

    @Test
    fun parseNestedObjectPositional() {
        val signature = FunctionSignature(
            name = "create_user",
            params = listOf(
                Param("user", ParamType.ObjectType(fields = listOf(
                    Param("name", ParamType.StringType(), required = true),
                    Param("age", ParamType.NumberType(), required = false),
                )), required = true)
            )
        )

        // 内层结构化 object 走位置模式
        val result = parser.parse("create_user(user={\"Alice\", 30})", signature)
        val user = result.jsonObject["user"]!!.jsonObject
        assertEquals("Alice", user["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, user["age"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun parseArrayOfObjectsPositional() {
        val signature = FunctionSignature(
            name = "create_users",
            params = listOf(
                Param("users", ParamType.ObjectType(isArray = true, fields = listOf(
                    Param("name", ParamType.StringType(), required = true),
                    Param("age", ParamType.NumberType(), required = false),
                )), required = true)
            )
        )

        // 数组元素也是 object,内部走位置模式
        val result = parser.parse("create_users(users=[{\"Alice\", 30}, {\"Bob\"}])", signature)
        val users = result.jsonObject["users"]!!.jsonArray
        assertEquals(2, users.size)
        assertEquals("Alice", users[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, users[0].jsonObject["age"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("Bob", users[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertNull(users[1].jsonObject["age"])
    }

    @Test
    fun parseNestedObjectPositionalExtraArgs() {
        val signature = FunctionSignature(
            name = "create_user",
            params = listOf(
                Param("user", ParamType.ObjectType(fields = listOf(
                    Param("name", ParamType.StringType(), required = true),
                    Param("age", ParamType.NumberType(), required = false),
                )), required = true)
            )
        )

        // 内层 object 也支持多余实参静默丢弃
        val result = parser.parse("create_user(user={\"Alice\", 30, \"extra\"})", signature)
        val user = result.jsonObject["user"]!!.jsonObject
        assertEquals("Alice", user["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, user["age"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(2, user.size)
    }

    @Test
    fun parseMixedNamedOuterPositionalInner() {
        // 外层 named + 内层 positional 混合:每层独立检测,互不干扰
        val signature = FunctionSignature(
            name = "create_user",
            params = listOf(
                Param("user", ParamType.ObjectType(fields = listOf(
                    Param("name", ParamType.StringType(), required = true),
                    Param("age", ParamType.NumberType(), required = false),
                )), required = true)
            )
        )

        val result = parser.parse("create_user(user={\"Alice\", 30})", signature)
        val user = result.jsonObject["user"]!!.jsonObject
        assertEquals("Alice", user["name"]?.jsonPrimitive?.content)
        assertEquals(30.0, user["age"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun parseExecutionPositionalInOneOfIgnored() {
        // oneOf 签名不走 positional 路径(guard 拦截)。
        // 验证:即使输入形如位置参数,oneOf 也不会尝试按 branch.params 顺序映射(那会产生空 {}),
        // 而是走 named 路径,产生带 key 的 JsonObject(因签名无 params,内容不规整但不爆炸)。
        val result = parser.parse("music_control(\"play\", \"海阔天空\")", musicControlOneOfSignature)

        // named 路径会保留首个 token 作为 key,所以结果非空;若 positional 误触发,结果会是空 {}
        assertTrue(result.jsonObject.isNotEmpty())
    }
}
