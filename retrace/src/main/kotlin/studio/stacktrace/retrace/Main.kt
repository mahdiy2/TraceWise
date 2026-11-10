package studio.stacktrace.retrace

import studio.stacktrace.RetraceStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Original input is read only; output goes to stdout for explicit redirection. */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: stacktrace-retrace <mapping.txt> <crash.txt>")
        exitProcess(2)
    }
    try {
        val input = Path.of(args[1])
        require(Files.size(input) <= 1024 * 1024) { "Crash log exceeds 1 MiB" }
        val result = R8RetraceEngine().retrace(Path.of(args[0]), Files.readString(input))
        if (result.status == RetraceStatus.ERROR) {
            System.err.println("${result.errorCode}: ${result.message}")
            exitProcess(1)
        }
        result.warnings.forEach(System.err::println)
        println(result.translated)
    } catch (_: Exception) {
        System.err.println("Unable to read input files. Check paths, permissions and file size.")
        exitProcess(1)
    }
}
