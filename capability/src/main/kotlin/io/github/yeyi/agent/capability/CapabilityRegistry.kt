package io.github.yeyi.agent.capability

import java.util.concurrent.ConcurrentHashMap

public interface CapabilityRegistry<Ctx : CapabilityContext, C : Capability<Ctx>> {
    public val capabilityName: String

    public fun register(capability: C): CapabilityRegistry<Ctx, C>

    public fun register(capabilities: List<C>): Unit =
        capabilities.forEach(::register)

    public fun all(): List<C>
}

public class DefaultCapabilityRegistry<Ctx : CapabilityContext, C : Capability<Ctx>>(
    override val capabilityName: String
) : CapabilityRegistry<Ctx, C> {
    private val capabilities: MutableMap<String, C> = ConcurrentHashMap()

    override fun register(capability: C): CapabilityRegistry<Ctx, C> {
        require(!capabilities.containsKey(capability.name)) { "$capabilityName with name '${capability.name}' is already registered" }
        capabilities[capability.name] = capability
        return this
    }

    override fun all(): List<C> = capabilities.values.toList()
}