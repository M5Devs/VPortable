package com.m5dev.vportable.terminal

/**
 * Circular scrollback buffer holding terminal output as lines.
 *
 * Only [maxLines] lines are retained; appending past the cap drops the oldest.
 * Text is appended into the last line so a partially received line is not
 * broken apart between reads.
 *
 * Pure Kotlin with no Android dependencies so it can be unit tested on the JVM.
 * All mutations are synchronised: the PTY reader writes from a coroutine while
 * the UI thread reads.
 */
class TerminalBuffer(val maxLines: Int = 800) {

    private val lines = ArrayDeque<String>(maxLines + 1)

    /**
     * Appends [text], treating a bare CR as an in-place overwrite of the current
     * line: apk and other progress bars redraw the same row with "\r" instead of
     * emitting a new line.
     */
    @Synchronized
    fun appendRaw(text: String) {
        if (text.isEmpty()) return

        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\r' -> {
                    // CRLF: the \n that follows handles the line break.
                    if (i + 1 < n && text[i + 1] == '\n') {
                        newline()
                        i += 2
                        continue
                    }
                    // Bare CR: restart the current line.
                    if (lines.isEmpty()) lines.addLast("")
                    lines[lines.size - 1] = ""
                }
                c == '\n' -> newline()
                else -> {
                    // Copy the run up to the next control character in one go so
                    // ordinary text costs one string concat per chunk, not per char.
                    var end = i
                    while (end < n && text[end] != '\r' && text[end] != '\n') end++
                    if (lines.isEmpty()) lines.addLast("")
                    lines[lines.size - 1] = lines[lines.size - 1] + text.substring(i, end)
                    i = end
                    continue
                }
            }
            i++
        }
    }

    private fun newline() {
        if (lines.size >= maxLines) lines.removeFirst()
        lines.addLast("")
    }

    /** Removes the last character of the current line, if any. */
    @Synchronized
    fun applyBackspace() {
        if (lines.isEmpty()) return
        val last = lines[lines.size - 1]
        if (last.isNotEmpty()) {
            lines[lines.size - 1] = last.dropLast(1)
        }
    }

    @Synchronized
    fun getVisibleLines(maxVisible: Int = maxLines): List<String> {
        if (maxVisible <= 0) return emptyList()
        return if (lines.size <= maxVisible) {
            lines.toList()
        } else {
            lines.takeLast(maxVisible).toList()
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun joinToString(): String = lines.joinToString("\n")

    val lineCount: Int
        @Synchronized get() = lines.size
}
