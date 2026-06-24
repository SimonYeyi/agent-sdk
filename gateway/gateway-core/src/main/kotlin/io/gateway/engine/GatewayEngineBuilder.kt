package io.gateway.engine

import io.gateway.api.AgentRunner
import io.gateway.api.ConcurrencyController
import io.gateway.api.DeliveryRouter
import io.gateway.api.GatewayEngine
import io.gateway.api.GatewaySessionManager
import io.gateway.api.HookPipeline
import io.gateway.model.GatewayConfig
import java.io.File

class GatewayEngineBuilder {

    private var config: GatewayConfig = GatewayConfig()
    private var sessionManager: GatewaySessionManager? = null
    private var agentRunner: AgentRunner? = null
    private var hookPipeline: HookPipeline? = null
    private var deliveryRouter: DeliveryRouter? = null
    private var concurrencyController: ConcurrencyController? = null
    private var baseDir: File? = null

    fun withConfig(config: GatewayConfig): GatewayEngineBuilder = apply {
        this.config = config
    }

    fun withSessionManager(manager: GatewaySessionManager): GatewayEngineBuilder = apply {
        this.sessionManager = manager
    }

    fun withFileSessionStorage(baseDir: File): GatewayEngineBuilder = apply {
        this.baseDir = baseDir
    }

    fun withAgentRunner(runner: AgentRunner): GatewayEngineBuilder = apply {
        this.agentRunner = runner
    }

    fun withHookPipeline(pipeline: HookPipeline): GatewayEngineBuilder = apply {
        this.hookPipeline = pipeline
    }

    fun withDeliveryRouter(router: DeliveryRouter): GatewayEngineBuilder = apply {
        this.deliveryRouter = router
    }

    fun withConcurrencyController(controller: ConcurrencyController): GatewayEngineBuilder = apply {
        this.concurrencyController = controller
    }

    fun build(): GatewayEngine {
        val engine = DefaultGatewayEngine(config)

        val sessionMgr = sessionManager
            ?: baseDir?.let { FileGatewaySessionManager(it) }
            ?: InMemoryGatewaySessionManager()
        engine.setSessionManager(sessionMgr)

        agentRunner?.let { engine.setAgentRunner(it) }
        hookPipeline?.let { engine.setHookPipeline(it) }
        deliveryRouter?.let { /* deliveryRouter 内部创建，这里不需要外部设置 */ }
        concurrencyController?.let { /* concurrencyController 内部创建，暂不支持外部替换 */ }

        return engine
    }
}
