package io.github.yeyi.agent.capability

import java.util.concurrent.ConcurrentHashMap

/**
 * Capability 注册中心接口。
 *
 * @param C capability 类型
 * @param T arguments 类型
 * @param Ctx 执行上下文类型
 */
public interface CapabilityRegistry<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext> {
    /** 注册中心名称，用于日志和错误信息。 */
    public val capabilityType: String

    /** 注册单个 capability，名称重复时抛 [IllegalArgumentException]。 */
    public fun register(capability: C)

    /** 批量注册。 */
    public fun register(capabilities: Iterable<C>)

    /** 按名称查找，找不到抛 [NoSuchElementException]。 */
    public fun get(name: String): C

    /** 按名称查找，找不到返回 null。 */
    public fun getOrNull(name: String): C?

    /** 返回所有已注册 capability。 */
    public fun all(): List<C>

    /** 是否为空（无任何注册）。 */
    public fun isEmpty(): Boolean

    /** 按名称移除单个注册,不存在时 no-op。 */
    public fun unregister(name: String)

    /** 清除所有注册。 */
    public fun unregisterAll()
}

/**
 * Default in-memory implementation of [CapabilityRegistry].
 *
 * Uses a [ConcurrentHashMap] for thread-safe storage. Registration is
 * typically single-threaded during agent setup.
 *
 * @param C capability type
 * @param T arguments type
 * @param Ctx context type
 */
public class DefaultCapabilityRegistry<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    override val capabilityType: String
) : CapabilityRegistry<C, T, Ctx> {
    private val capabilities: MutableMap<String, C> = ConcurrentHashMap()

    override fun register(capability: C) {
        require(!capabilities.containsKey(capability.name)) { "$capabilityType with name '${capability.name}' is already registered" }
        capabilities[capability.name] = capability
    }

    override fun register(capabilities: Iterable<C>): Unit = capabilities.forEach(::register)

    override fun get(name: String): C {
        return capabilities[name]
            ?: throw NoSuchElementException("$capabilityType with name '$name' not found")
    }

    override fun getOrNull(name: String): C? = capabilities[name]

    override fun all(): List<C> = capabilities.values.toList()

    override fun isEmpty(): Boolean = capabilities.isEmpty()

    override fun unregister(name: String) {
        capabilities.remove(name)
    }

    override fun unregisterAll(): Unit = capabilities.clear()
}
