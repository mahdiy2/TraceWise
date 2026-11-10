package studio.stacktrace.utils

/** Display-only ranges; these never interpret mapping files or change retrace output. */
enum class TraceTokenKind { EXCEPTION, CLASS, METHOD, SOURCE, LINE }
data class TraceToken(val start: Int,val end: Int,val kind: TraceTokenKind)
private val frame=Regex("(?m)^\\s*at\\s+([\\w.$/]+)\\.([\\w$<>-]+)\\(([^():\\r\\n]+)(?::(\\d+))?\\)")
private val exception=Regex("\\b[\\w.$]+(?:Exception|Error)\\b")
fun traceTokens(text: String): List<TraceToken> = buildList {
    frame.findAll(text).forEach { match ->
        listOf(TraceTokenKind.CLASS,TraceTokenKind.METHOD,TraceTokenKind.SOURCE,TraceTokenKind.LINE).forEachIndexed { index,kind ->
            match.groups[index+1]?.let { add(TraceToken(it.range.first,it.range.last+1,kind)) }
        }
    }
    exception.findAll(text).forEach {add(TraceToken(it.range.first,it.range.last+1,TraceTokenKind.EXCEPTION))}
}
class TraceDisplay private constructor(val text: String,private val forward: IntArray,private val reverse: IntArray) {
    fun originalOffset(offset: Int): Int = forward[offset.coerceIn(0,forward.lastIndex)]
    fun displayOffset(offset: Int): Int = reverse[offset.coerceIn(0,reverse.lastIndex)]
    companion object {
        /** A tab displays as four spaces; all editing/export positions retain original text. */
        fun from(original: String): TraceDisplay {
            val forward=IntArray(original.length+1)
            val builder=StringBuilder(original.length)
            original.forEachIndexed { index,char ->
                forward[index]=builder.length
                if(char=='\t')builder.append("    ") else builder.append(char)
            }
            forward[original.length]=builder.length
            val reverse=IntArray(builder.length+1)
            original.indices.forEach { index ->
                for(display in forward[index] until forward[index+1])reverse[display]=index
            }
            reverse[builder.length]=original.length
            return TraceDisplay(builder.toString(),forward,reverse)
        }
    }
}
