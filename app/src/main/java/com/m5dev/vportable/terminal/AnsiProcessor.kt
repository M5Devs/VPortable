package com.m5dev.vportable.terminal

/**
 * Strips ANSI/VT100 escape sequences from raw PTY output.
 *
 * Covers what Alpine's busybox and apk actually emit: CSI (colours, cursor
 * movement, erase), OSC (window titles, hyperlinks), and the short two-byte
 * escapes such as ESC= / ESC> / ESC(B. Rendering is line-oriented rather than
 * cell-oriented, so sequences are removed rather than interpreted.
 */
object AnsiProcessor {

    // CSI: ESC [ parameters intermediates final-byte. Parameters may include
    // ';' ':' '?' and the '<' '=' '>' private prefixes; intermediates are 0x20-0x2F.
    private val CSI = Regex("\u001B\\[[0-9;:?<=>]*[ -/]*[@-~]")

    // OSC: ESC ] ... terminated by BEL or ST (ESC \).
    private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?")

    // Remaining two-byte escapes, e.g. ESC= ESC> ESC(B. The optional trailing
    // character is only consumed for the character-set designators ESC( and
    // ESC), which take one more byte; other short escapes are exactly two bytes.
    private val ESC_SHORT = Regex("\u001B[=>78DEHMNOZc]")
    private val ESC_CHARSET = Regex("\u001B[()][0-9A-Za-z]")

    fun strip(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        if (s.indexOf('\u001B') >= 0) {
            s = OSC.replace(s, "")
            s = CSI.replace(s, "")
            s = ESC_CHARSET.replace(s, "")
            s = ESC_SHORT.replace(s, "")
            // Any escape left over (malformed or unhandled): drop the ESC so it
            // cannot leak into the rendered text as a visible glyph.
            if (s.indexOf('\u001B') >= 0) s = s.replace("\u001B", "")
        }
        // Control characters that carry no printable meaning here.
        s = s.replace("\u0007", "")
            .replace("\u0000", "")
            .replace("\u0011", "")
            .replace("\u0013", "")
        return s
    }
}
