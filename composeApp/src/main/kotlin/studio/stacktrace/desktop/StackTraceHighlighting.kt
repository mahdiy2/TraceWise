package studio.stacktrace.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import studio.stacktrace.utils.*

/** Shared display tokens and reversible tab expansion; deobfuscation remains exclusively in R8. */
class StackTraceHighlighting(private val exceptionColor: Color,private val locationColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val display=TraceDisplay.from(text.text)
        val builder=AnnotatedString.Builder(display.text)
        traceTokens(display.text).forEach { token ->
            val style=when(token.kind) {
                TraceTokenKind.EXCEPTION -> SpanStyle(color=exceptionColor,fontWeight=FontWeight.SemiBold)
                TraceTokenKind.CLASS -> SpanStyle(color=locationColor)
                TraceTokenKind.METHOD -> SpanStyle(color=locationColor,fontWeight=FontWeight.SemiBold)
                TraceTokenKind.SOURCE -> SpanStyle(color=locationColor.copy(alpha=0.85f))
                TraceTokenKind.LINE -> SpanStyle(color=exceptionColor)
            }
            builder.addStyle(style,token.start,token.end)
        }
        return TransformedText(builder.toAnnotatedString(),object : OffsetMapping {
            override fun originalToTransformed(offset: Int)=display.originalOffset(offset)
            override fun transformedToOriginal(offset: Int)=display.displayOffset(offset)
        })
    }
}
