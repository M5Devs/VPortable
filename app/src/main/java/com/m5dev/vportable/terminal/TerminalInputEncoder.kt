package com.m5dev.vportable.terminal

/**
 * Maps committed text and control keys to the exact byte sequences a terminal
 * expects. Deliberately free of Android dependencies so it can be unit tested on
 * the JVM: the IME plumbing in [TerminalInputView] only decides *when* input
 * happened, this decides *what bytes* it becomes.
 */
object TerminalInputEncoder {

    val ENTER = byteArrayOf(0x0D) // '\r'; ICRNL turns it into '\n' in the PTY
    val BACKSPACE = byteArrayOf(0x7F) // DEL
    val ESCAPE = byteArrayOf(0x1B)
    val TAB = byteArrayOf(0x09)
    val CTRL_C = byteArrayOf(0x03)

    val ARROW_UP = byteArrayOf(0x1B, 0x5B, 0x41)
    val ARROW_DOWN = byteArrayOf(0x1B, 0x5B, 0x42)
    val ARROW_RIGHT = byteArrayOf(0x1B, 0x5B, 0x43)
    val ARROW_LEFT = byteArrayOf(0x1B, 0x5B, 0x44)

    /**
     * Splits committed [text] into ordered byte chunks. Newlines are emitted as
     * [ENTER] so a single IME commit of "ls\n" sends the command and the carriage
     * return separately, in order. `\r\n` counts as one newline.
     */
    fun encodeText(text: CharSequence): List<ByteArray> {
        if (text.isEmpty()) return emptyList()

        val chunks = ArrayList<ByteArray>()
        val pending = StringBuilder()

        fun flush() {
            if (pending.isNotEmpty()) {
                chunks.add(pending.toString().toByteArray(Charsets.UTF_8))
                pending.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                '\r' -> {
                    flush()
                    chunks.add(ENTER)
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                '\n' -> {
                    flush()
                    chunks.add(ENTER)
                }
                else -> pending.append(ch)
            }
            i++
        }
        flush()
        return chunks
    }

    /**
     * Ctrl+letter becomes the corresponding control byte: Ctrl+A is 0x01 through
     * Ctrl+Z is 0x1A. Returns null for anything that is not a letter.
     */
    fun encodeCtrl(letter: Char): ByteArray? {
        val lower = letter.lowercaseChar()
        if (lower !in 'a'..'z') return null
        return byteArrayOf((lower - 'a' + 1).toByte())
    }
}

/**
 * Turns the stream of IME callbacks for one editing session into the delta bytes
 * a terminal should receive.
 *
 * A soft keyboard does not simply report "the user pressed k". It maintains a
 * composition region and calls [setComposingText] repeatedly as the region grows
 * ("l", then "ls"), then [commitText] when the word is accepted. Forwarding each
 * callback verbatim would send every prefix, so the committed text is diffed
 * against what was already sent for the current composition and only the change
 * is emitted.
 */
class TerminalTextCommitter {

    private val composing = StringBuilder()

    /** Bytes to send for a composing update; only the changed suffix is produced. */
    fun setComposing(text: CharSequence): List<ByteArray> {
        val target = text.toString()
        val out = diffTo(target)
        composing.setLength(0)
        composing.append(target)
        return out
    }

    /** Bytes to send when the composition is accepted; clears the composition. */
    fun commit(text: CharSequence): List<ByteArray> {
        val out = diffTo(text.toString())
        composing.setLength(0)
        return out
    }

    /**
     * Bytes to send when the IME abandons the composition without committing.
     * The characters were already delivered as they were composed, so nothing
     * more is sent and the composition state is dropped.
     */
    fun finish(): List<ByteArray> {
        composing.setLength(0)
        return emptyList()
    }

    /** Drops composition state after out-of-band input (hardware keys, toolbars). */
    fun reset() {
        composing.setLength(0)
    }

    private fun diffTo(target: String): List<ByteArray> {
        val current = composing.toString()
        val common = commonPrefixLength(current, target)

        val out = ArrayList<ByteArray>()
        repeat(current.length - common) { out.add(TerminalInputEncoder.BACKSPACE) }
        if (target.length > common) {
            out.addAll(TerminalInputEncoder.encodeText(target.substring(common)))
        }
        return out
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }
}

