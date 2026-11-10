package studio.stacktrace.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val config = ServerConfig.environment()
    embeddedServer(Netty, port=config.port, host=if(config.development) "127.0.0.1" else "0.0.0.0") {
        studioModule(config)
    }.start(wait=true)
}
