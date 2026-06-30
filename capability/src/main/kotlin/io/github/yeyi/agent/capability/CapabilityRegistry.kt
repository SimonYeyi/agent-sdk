package io.github.yeyi.agent.capability

import java.util.concurrent.ConcurrentHashMap

public interface CapabilityRegistry<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any> {
    public val capabilityName: String

    public fun register(capability: C)

    public fun register(capabilities: Iterable<C>)

    public fun get(name: String): C

    public fun all(): List<C>

    public fun unregisterAll()
}

public class DefaultCapabilityRegistry<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    override val capabilityName: String
) : CapabilityRegistry<Ctx, C, T> {
    private val capabilities: MutableMap<String, C> = ConcurrentHashMap()

    override fun register(capability: C) {
        require(!capabilities.containsKey(capability.name)) { "$capabilityName with name '${capability.name}' is already registered" }
        capabilities[capability.name] = capability
    }

    override fun register(capabilities: Iterable<C>): Unit = capabilities.forEach(::register)

    override fun get(name: String): C {
        return capabilities[name]
            ?: throw NoSuchElementException("$capabilityName with name '$name' not found")
    }

    override fun all(): List<C> = capabilities.values.toList()

    override fun unregisterAll(): Unit = capabilities.clear()
}