package com.m5dev.vportable.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTextCommitterTest {

    private fun List<ByteArray>.concat(): String {
        val out = ArrayList<Byte>()
        for (chunk in this) chunk.forEach { out.add(it) }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    @Test
    fun `gboard style typing sends each letter exactly once`() {
        // Gboard grows the composition one character at a time and then commits
        // the whole word. The shell must see "ls" once, not "llsls".
        val committer = TerminalTextCommitter()
        val sent = StringBuilder()

        sent.append(committer.setComposing("l").concat())
        sent.append(committer.setComposing("ls").concat())
        sent.append(committer.commit("ls").concat())

        assertEquals("ls", sent.toString())
    }

    @Test
    fun `a single keystroke reported twice is not duplicated`() {
        val committer = TerminalTextCommitter()
        val sent = StringBuilder()
        sent.append(committer.setComposing("a").concat())
        sent.append(committer.commit("a").concat())
        assertEquals("a", sent.toString())
    }

    @Test
    fun `replacing a composition backs out the old text first`() {
        val committer = TerminalTextCommitter()
        val sent = StringBuilder()
        sent.append(committer.setComposing("teh").concat())
        sent.append(committer.setComposing("the").concat())
        // "teh" -> common prefix "t", so "eh" is erased and "he" typed.
        assertEquals("teh\u007F\u007Fhe", sent.toString())
    }

    @Test
    fun `finishing a composition without committing sends nothing extra`() {
        val committer = TerminalTextCommitter()
        committer.setComposing("hello")
        assertTrue(committer.finish().isEmpty())
    }

    @Test
    fun `reset drops composition so the next commit is not diffed against it`() {
        val committer = TerminalTextCommitter()
        committer.setComposing("ab")
        committer.reset()
        assertEquals("cd", committer.commit("cd").concat())
    }

    @Test
    fun `a committed newline becomes one carriage return`() {
        val committer = TerminalTextCommitter()
        val chunks = committer.commit("ls\n")
        assertEquals("ls", chunks[0].toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x0D), chunks[1])
    }

    @Test
    fun `typing a word then enter sends the word once then a return`() {
        val committer = TerminalTextCommitter()
        val sent = StringBuilder()
        sent.append(committer.setComposing("e").concat())
        sent.append(committer.setComposing("ec").concat())
        sent.append(committer.setComposing("ech").concat())
        sent.append(committer.setComposing("echo").concat())
        sent.append(committer.commit("echo").concat())
        sent.append(committer.commit("\n").concat())
        assertEquals("echo", sent.toString().substringBefore('\u000D'))
        assertEquals(1, sent.count { it == '\u000D' })
    }
}
