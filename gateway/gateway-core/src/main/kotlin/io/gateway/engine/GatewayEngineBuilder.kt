package io.gateway.engine

import io.gateway.api.AgentRunner
import io.gateway.api.ConcurrencyController
import io.gateway.api.DeliveryRouter
import io.gateway.api.GatewayEngine
import io.gateway.api.GatewaySessionManager
import io.gateway.api.HookPipeline
import io.gateway.model.GatewayConfig
import java.io.File

public class GatewayEngineBuilder {

    private var config: GatewayConfig = GatewayConfig()
    private var sessionManager: GatewaySessionManager? = null
    private var agentRunner: AgentRunner? = null
    private var hookPipeline: HookPipeline? = null
    private var deliveryRouter: DeliveryRouter? = null
    private var concurrencyController: ConcurrencyController? = null
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

    public fun withDeliveryRouter(router: DeliveryRouter): GatewayEngineBuilder = apply {
        this.deliveryRouter = router
    }

    public fun withConcurrencyController(controller: ConcurrencyController): GatewayEngineBuilder = apply {
        this.concurrencyController = controller
    }

    public fun build(): GatewayEngine {
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
