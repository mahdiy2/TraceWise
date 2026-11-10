package studio.stacktrace.retrace

import kotlin.test.*
import java.nio.file.Files
import studio.stacktrace.RetraceStatus

class R8RetraceEngineTest {
    private val engine = R8RetraceEngine()
    private fun withMapping(body: (java.nio.file.Path) -> Unit) {
        val path = Files.createTempFile("retrace-test", ".map")
        try {
            Files.writeString(path, """
                com.example.Crash -> a:
                # {"id":"sourceFile","fileName":"Crash.kt"}
                    1:1:void explode():42:42 -> b
                    2:2:void inlineCall():17:17 -> c
                    2:2:void caller():55:55 -> c
            """.trimIndent())
            body(path)
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun restoresMethodAndLine() = withMapping { p ->
        val original = "java.lang.IllegalStateException: boom\n\tat a.b(SourceFile:1)"
        val result = engine.retrace(p, original)
        assertEquals(RetraceStatus.SUCCESS, result.status)
        assertEquals(original, result.original)
        assertContains(result.translated!!, "com.example.Crash.explode(Crash.kt:42)")
    }
    @Test fun expandsInlineFrames() = withMapping { p ->
        val text = engine.retrace(p, "\tat a.c(SourceFile:2)").translated!!
        assertContains(text, "inlineCall(Crash.kt:17)")
        assertContains(text, "caller(Crash.kt:55)")
    }
    @Test fun batchPreservesFailuresAndUnknownFrames() = withMapping { p ->
        val unknown = "\tat android.app.Activity.onCreate(Activity.java:12)"
        val results = engine.retraceBatch(p, listOf(unknown, "", "\tat a.b(SourceFile:1)"))
        assertEquals(unknown, results[0].translated)
        assertEquals("EMPTY_TRACE", results[1].errorCode)
        assertEquals(RetraceStatus.SUCCESS, results[2].status)
    }
    @Test fun nestedExceptionsAndUnknownLine() = withMapping { p ->
        val result = engine.retrace(p, "java.lang.RuntimeException\nCaused by: java.lang.IllegalStateException\n\tat a.b(SourceFile:999)\n\tSuppressed: java.lang.NullPointerException\n\tat a.b(SourceFile:1)")
        assertEquals(RetraceStatus.SUCCESS, result.status)
        assertContains(result.translated!!, "Caused by: java.lang.IllegalStateException")
        assertContains(result.translated!!, "Suppressed: java.lang.NullPointerException")
        assertContains(result.translated!!, "com.example.Crash.explode")
    }
    @Test fun coroutineFramesAndKotlinExceptionRemainReadable() = withMapping { p ->
        val frame="\tat kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:106)"
        val continuation="\tat kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)"
        val original="kotlin.KotlinNullPointerException\n\tat a.b(SourceFile:1)\n$frame\n$continuation"
        val result=engine.retrace(p,original)
        assertEquals(original,result.original)
        assertContains(result.translated!!,"com.example.Crash.explode(Crash.kt:42)")
        assertContains(result.translated!!,frame)
        assertContains(result.translated!!,continuation)
    }
    @Test fun sharedEngineDoesNotMixConcurrentMappings() = withMapping { first ->
        val second=Files.createTempFile("other-mapping",".txt")
        val executor=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            Files.writeString(second,"com.other.Service -> a:\n    1:1:void execute():99:99 -> b\n")
            val tasks=(0 until 20).map { index -> java.util.concurrent.Callable {
                val useFirst=index%2==0
                val result=engine.retrace(if(useFirst) first else second,"\tat a.b(SourceFile:1)")
                assertContains(result.translated!!,if(useFirst) "com.example.Crash.explode(Crash.kt:42)" else "com.other.Service.execute(Service.java:99)")
            } }
            executor.invokeAll(tasks).forEach {it.get(10,java.util.concurrent.TimeUnit.SECONDS)}
        } finally {executor.shutdownNow();Files.deleteIfExists(second)}
    }
    @Test fun retracesActualCrashProducedByPinnedR8Compiler() {
        val mapping=Files.createTempFile("generated-r8",".map")
        try {
            javaClass.getResourceAsStream("/generated/mapping.txt")!!.use {Files.write(mapping,it.readBytes())}
            val original=javaClass.getResourceAsStream("/generated/trace.txt")!!.bufferedReader().use {it.readText()}
            val result=engine.retrace(mapping,original)
            assertEquals(RetraceStatus.SUCCESS,result.status)
            assertEquals(original,result.original)
            assertContains(result.translated!!,"sample.Crash.explode(Crash.java:4)")
            assertContains(result.translated!!,"sample.Main.main(Main.java:4)")
        } finally {Files.deleteIfExists(mapping)}
    }
    @Test fun handlesAndroidLogcatPrefixesWithoutLosingContext() = withMapping { mapping ->
        val prefix="11-10 12:34:56.789 1234 1234 E AndroidRuntime: "
        val original=prefix+"java.lang.IllegalStateException: crash\n"+prefix+"\tat a.b(SourceFile:1)"
        val result=engine.retrace(mapping,original)
        assertEquals(original,result.original)
        assertContains(result.translated!!,"com.example.Crash.explode(Crash.kt:42)")
        assertContains(result.translated!!,prefix)
    }
    @Test fun missingMapping() {
        assertEquals("MAPPING_MISSING", engine.retrace(java.nio.file.Path.of("absent-mapping.txt"), "trace").errorCode)
    }
    @Test fun invalidMapping() {
        val p = Files.createTempFile("invalid", ".map")
        try {
            Files.writeString(p, "this is not a valid mapping")
            assertEquals("INVALID_MAPPING", engine.retrace(p, "trace").errorCode)
        } finally { Files.deleteIfExists(p) }
    }
}
