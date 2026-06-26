package io.gateway.engine

import io.gateway.api.AgentRunner
import io.gateway.api.GatewayEngine
import io.gateway.api.GatewaySessionManager
import io.gateway.api.HookPipeline
import io.gateway.model.GatewayConfig
import io.gateway.util.GatewayLogDelegate
import io.gateway.util.GatewayLogging
import java.io.File

public class GatewayEngineBuilder {

    private var config: GatewayConfig = GatewayConfig()
    private var sessionManager: GatewaySessionManager? = null
    private var agentRunner: AgentRunner? = null
    private var hookPipeline: HookPipeline? = null
    private var logDelegate: GatewayLogDelegate? = null
    private var baseDir: File? = null

    public fun withConfig(config: GatewayConfig): GatewayEngineBuilder = apply {
        this.config = config
    }

    public fun withSessionManager(manager: GatewaySessionManager): GatewayEngineBuilder = apply {
        this.sessionManager = manager
    }

    public fun withFileSessionStorage(baseDir: File): GatewayEngineBuilder = apply {
        this.baseDir = baseDir
    }

    public fun withAgentRunner(runner: AgentRunner): GatewayEngineBuilder = apply {
        this.agentRunner = runner
    }

    public fun withHookPipeline(pipeline: HookPipeline): GatewayEngineBuilder = apply {
        this.hookPipeline = pipeline
    }

    public fun withLogDelegate(delegate: GatewayLogDelegate): GatewayEngineBuilder = apply {
        this.logDelegate = delegate
    }

    public fun build(): GatewayEngine {
        logDelegate?.let { GatewayLogging.setDelegate(it) }

        val engine = DefaultGatewayEngine(config)

        val sessionMgr = sessionManager
            ?: baseDir?.let { FileGatewaySessionManager(it) }
            ?: InMemoryGatewaySessionManager()
        engine.setSessionManager(sessionMgr)

        agentRunner?.let { engine.setAgentRunner(it) }
        hookPipeline?.let { engine.setHookPipeline(it) }

        return engine
    }
}
