package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityInstallerTest {

    private class StubContext : CapabilityContext

    private class StubContextFactory : CapabilityContextFactory<StubContext> {
        override fun create(context: ToolContext): StubContext = StubContext()
    }

    private class StubCapability(
        override val name: String,
        override val description: String,
    ) : Capability<Unit, StubContext> {
        override suspend fun activate(arguments: Unit?, context: StubContext): String =
            "stub activated: $name"
    }

    private class StubAuxTool(private val toolName: String) : Tool {
        override val name: String get() = toolName
        override val description: String = "stub $toolName"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("")
    }

    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    private fun minimalInstaller(
        registry: CapabilityRegistry<StubCapability, Unit, StubContext> =
            DefaultCapabilityRegistry<StubCapability, Unit, StubContext>("stub").apply { register(StubCapability("a", "desc a")) },
        auxiliaryTools: List<Tool> = emptyList(),
    ): CapabilityInstaller<StubCapability, Unit, StubContext> =
        object : CapabilityInstaller<StubCapability, Unit, StubContext>() {
            override fun registry(): CapabilityRegistry<StubCapability, Unit, StubContext> = registry
            override fun contextFactory() = StubContextFactory()
            override fun arguments(): CapabilityArguments<Unit>? = null
            override fun auxiliaryTools(): List<Tool> = auxiliaryTools
        }

    private fun emptyBuilder(): AgentBuilder = AgentBuilder()

    @Test
    fun `installer exposes registry passed in constructor`() {
        val registry = DefaultCapabilityRegistry<StubCapability, Unit, StubContext>("stub")
        val installer = minimalInstaller(registry)
        val m = CapabilityInstaller::class.java.getDeclaredMethod("registry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        assertEquals(registry, m.invoke(installer))
    }

    @Test
    fun `installOn in delegate mode installs load_stub tool`() {
        val installer = minimalInstaller()
        val builder = emptyBuilder()
        installer.installOn(builder, enableDelegateAdaptMode = true)
        val toolNames = builder.installedTools().map { it.name }
        assertTrue("load_stub" in toolNames, "expected load_stub, got $toolNames")
    }

    @Test
    fun `installOn in one-to-one mode installs per-capability tool`() {
        val registry = DefaultCapabilityRegistry<StubCapability, Unit, StubContext>("stub").apply {
            register(StubCapability("alpha", "d"))
            register(StubCapability("beta", "d"))
        }
        val installer = minimalInstaller(registry)
        val builder = emptyBuilder()
        installer.installOn(builder, enableDelegateAdaptMode = false)
        val toolNames = builder.installedTools().map { it.name }
        assertTrue("stub_alpha" in toolNames, "expected stub_alpha, got $toolNames")
        assertTrue("stub_beta" in toolNames, "expected stub_beta, got $toolNames")
        assertFalse("load_stub" in toolNames, "delegate tool should NOT be installed in one-to-one mode")
    }

    @Test
    fun `installOn installs auxiliaryTools after CapabilityAdapter`() {
        val aux = StubAuxTool("aux_helper")
        val installer = minimalInstaller(auxiliaryTools = listOf(aux))
        val builder = emptyBuilder()
        installer.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertTrue("aux_helper" in toolNames, "expected aux_helper, got $toolNames")
        assertTrue("load_stub" in toolNames, "delegate tool must still be installed alongside aux tools")
    }

    @Test
    fun `installOn with empty auxiliaryTools installs only the load tool`() {
        val installer = minimalInstaller(auxiliaryTools = emptyList())
        val builder = emptyBuilder()
        installer.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertEquals(listOf("load_stub"), toolNames)
    }
}