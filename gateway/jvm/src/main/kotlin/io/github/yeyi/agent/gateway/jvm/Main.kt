package io.github.yeyi.agent.gateway.jvm

fun main() {
    val config = GatewayDaemonConfig.load()
    val daemon = GatewayDaemon(config)

    Runtime.getRuntime().addShutdownHook(Thread {
        daemon.stop()
    })

    daemon.start()
}
