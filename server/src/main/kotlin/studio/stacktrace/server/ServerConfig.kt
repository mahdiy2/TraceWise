package studio.stacktrace.server

import java.nio.file.Path

data class ServerConfig(
    val storage: Path,
    val token: String?,
    val development: Boolean = false,
    val port: Int = 8080,
    val mappingLimit: Long = 50L * 1024 * 1024,
    val apkLimit: Long = 500L * 1024 * 1024,
    val concurrency: Int = 2,
    val webDirectory: Path? = null,
) {
    init {
        require(development || !token.isNullOrBlank()) { "Set STACKTRACE_API_TOKEN or enable explicit localhost development mode" }
        require(token == null || token.isNotBlank()) { "Configured API token must not be blank; unset it for unauthenticated development" }
        webDirectory?.let { web ->
            require(!storage.toAbsolutePath().normalize().startsWith(web.toAbsolutePath().normalize())) { "Web directory must not contain private storage" }
        }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(mappingLimit>0 && apkLimit>0) { "Artifact size limits must be positive" }
        require(concurrency in 1..32) { "Retrace concurrency must be between 1 and 32" }
    }
    companion object {
        fun environment(): ServerConfig {
            fun number(name: String, default: Long) = System.getenv(name)?.let { it.toLongOrNull() ?: error("Invalid $name") } ?: default
            return ServerConfig(
                Path.of(System.getenv("STACKTRACE_DATA_DIR") ?: "data"),
                System.getenv("STACKTRACE_API_TOKEN"),
                System.getenv("STACKTRACE_DEV_MODE") == "true",
                number("PORT",8080).also { require(it in 1..65535) }.toInt(),
                number("STACKTRACE_MAPPING_LIMIT",50L*1024*1024),
                number("STACKTRACE_APK_LIMIT",500L*1024*1024),
                number("STACKTRACE_CONCURRENCY",2).also { require(it in 1..32) }.toInt(),
                System.getenv("STACKTRACE_WEB_DIR")?.let { Path.of(it) },
            )
        }
    }
}
