package com.m5dev.vportable.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CanvasForeground = 0xFFF8F8F2.toInt()
private val CanvasBackground = Color(0xFF000000)

/**
 * Draws terminal [lines] line-by-line with the platform text renderer.
 *
 * Only the lines that actually fit on screen are drawn, so cost is bounded by
 * the viewport rather than by how much output the session has produced. This is
 * the same approach Termux's renderer takes, and it replaces rendering the whole
 * scrollback as one `Text` node.
 *
 * The viewport is bottom-anchored: when the content is taller than the canvas the
 * last lines are shown, which is where the prompt is.
 */
@Composable
fun TerminalCanvas(
    lines: List<String>,
    isCursorVisible: Boolean,
    cursorAlpha: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { 13.sp.toPx() }
    val paddingH = with(density) { 8.dp.toPx() }
    val paddingV = with(density) { 4.dp.toPx() }

    // Keyed on the size so a font-scale or density change rebuilds the paint
    // instead of drawing at the old text size.
    val textPaint = remember(fontSizePx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = CanvasForeground
            textSize = fontSizePx
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }

    val cursorPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = false
            color = CanvasForeground
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
    ) {
        drawIntoCanvas { canvas ->
            val nCanvas = canvas.nativeCanvas
            val metrics = textPaint.fontMetrics
            val lineHeight = textPaint.fontSpacing
            // Distance from a line's top edge down to its baseline.
            val baselineFromTop = -metrics.ascent

            val usableHeight = size.height - paddingV * 2
            val maxLinesThatFit = if (lineHeight <= 0f) 0
            else (usableHeight / lineHeight).toInt().coerceAtLeast(0)

            if (maxLinesThatFit == 0) return@drawIntoCanvas

            // Short output stays at the top; long output shows its tail.
            val firstVisible = (lines.size - maxLinesThatFit).coerceAtLeast(0)
            val visible = if (firstVisible == 0) lines else lines.subList(firstVisible, lines.size)

            val maxChars = if (textPaint.measureText("M") > 0f)
                ((size.width - paddingH * 2) / textPaint.measureText("M")).toInt().coerceAtLeast(1)
            else Int.MAX_VALUE

            var y = paddingV + baselineFromTop
            for (line in visible) {
                // Long lines are clipped to the viewport width so the text engine
                // never has to shape an unbounded string.
                val drawable = if (line.length > maxChars) line.substring(0, maxChars) else line
                nCanvas.drawText(drawable, paddingH, y, textPaint)
                y += lineHeight
            }

            if (isCursorVisible && cursorAlpha > 0f) {
                val lastLine = visible.lastOrNull().orEmpty()
                val drawn = if (lastLine.length > maxChars) lastLine.substring(0, maxChars) else lastLine
                val lastBaseline = paddingV + baselineFromTop + (visible.size - 1).coerceAtLeast(0) * lineHeight
                val cursorX = paddingH + textPaint.measureText(drawn)
                val cursorTop = lastBaseline + metrics.ascent
                val cursorWidth = textPaint.measureText("M")
                val cursorHeight = lineHeight

                cursorPaint.alpha = (cursorAlpha.coerceIn(0f, 1f) * 255).toInt()
                nCanvas.drawRect(
                    cursorX,
                    cursorTop,
                    cursorX + cursorWidth,
                    cursorTop + cursorHeight,
                    cursorPaint
                )
            }
        }
    }
}
