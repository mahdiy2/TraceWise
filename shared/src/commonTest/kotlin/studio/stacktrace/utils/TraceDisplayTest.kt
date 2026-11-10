package studio.stacktrace.utils

import kotlin.test.*

class TraceDisplayTest {
    @Test fun expandedTabsKeepEveryOriginalCursorPosition() {
        val original="\tat a.b(Crash.kt:42)\n\t\tend"
        val display=TraceDisplay.from(original)
        assertEquals(original.replace("\t","    "),display.text)
        for(offset in 0..original.length)assertEquals(offset,display.displayOffset(display.originalOffset(offset)))
        assertEquals(0,display.displayOffset(3))
        assertEquals(original.length,display.displayOffset(display.text.length))
    }
    @Test fun distinguishesClassMethodSourceLineAndExceptionWithoutChangingText() {
        val text="java.lang.IllegalStateException\n    at com.example.Crash.explode(Crash.kt:42)"
        val parts=traceTokens(text).associate {it.kind to text.substring(it.start,it.end)}
        assertEquals("java.lang.IllegalStateException",parts[TraceTokenKind.EXCEPTION])
        assertEquals("com.example.Crash",parts[TraceTokenKind.CLASS])
        assertEquals("explode",parts[TraceTokenKind.METHOD])
        assertEquals("Crash.kt",parts[TraceTokenKind.SOURCE])
        assertEquals("42",parts[TraceTokenKind.LINE])
    }
    @Test fun emptyAndUnrecognizedTextRemainUnmodified() {
        assertEquals("",TraceDisplay.from("").text)
        assertTrue(traceTokens("ordinary log message").isEmpty())
        assertEquals(0,TraceDisplay.from("").displayOffset(0))
    }
}
