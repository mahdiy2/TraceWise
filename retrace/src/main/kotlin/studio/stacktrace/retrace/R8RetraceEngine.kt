package studio.stacktrace.retrace

import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.retrace.*
import studio.stacktrace.RetraceResult
import studio.stacktrace.RetraceStatus
import java.nio.file.Files
import java.nio.file.Path

/** Blocking JVM engine. Call on an IO dispatcher; all R8 state is operation-local. */
class R8RetraceEngine(private val mappingLimit: Long = 50L*1024*1024) {
    fun retrace(mapping: Path, trace: String): RetraceResult = retraceBatch(mapping, listOf(trace)).single()

    fun retraceBatch(mapping: Path, traces: List<String>): List<RetraceResult> {
        require(traces.size <= 100) { "A batch may contain at most 100 traces" }
        require(traces.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= 10L * 1024 * 1024) {
            "Batch exceeds 10 MiB"
        }
        fun failure(trace: String, code: String, message: String) =
            RetraceResult(trace, null, RetraceStatus.ERROR, code, message)
        if (!Files.isRegularFile(mapping)) return traces.map { failure(it, "MAPPING_MISSING", "Mapping file is missing") }
        if (Files.size(mapping) !in 1..mappingLimit) return traces.map {
            failure(it, "INVALID_MAPPING", "Mapping is empty or exceeds the configured limit")
        }
        val warnings = mutableListOf<String>()
        val diagnostics = object : DiagnosticsHandler {
            override fun info(diagnostic: Diagnostic) { /* Never log mapping source details. */ }
            override fun warning(diagnostic: Diagnostic) { warnings += "R8 reported a mapping warning" }
            override fun error(diagnostic: Diagnostic) { throw IllegalArgumentException("Invalid mapping") }
        }
        val retracer = try {
            val supplier = ProguardMappingSupplier.builder()
                .setProguardMapProducer(ProguardMapProducer.fromPath(mapping))
                .setLoadAllDefinitions(true).build()
            supplier.createRetracer(diagnostics) // Force official parsing before processing any input.
            StringRetrace.create(RetraceOptions.builder(diagnostics).setMappingSupplier(supplier).build())
        } catch (_: Exception) {
            return traces.map { failure(it, "INVALID_MAPPING", "Mapping could not be read by R8") }
        }
        return traces.map { original ->
            when {
                original.isBlank() -> failure(original, "EMPTY_TRACE", "Stack trace is empty")
                original.toByteArray(Charsets.UTF_8).size > 1024 * 1024 ->
                    failure(original, "TRACE_TOO_LARGE", "Stack trace exceeds 1 MiB")
                else -> try {
                    val translated = retracer.retrace(original.lines(), RetraceStackTraceContext.empty()).result.joinToString("\n")
                    RetraceResult(original, translated, RetraceStatus.SUCCESS, warnings = warnings.toList())
                } catch (_: Exception) {
                    failure(original, "RETRACE_FAILED", "R8 could not translate this stack trace")
                }
            }
        }
    }
}
