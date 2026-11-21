package com.m5dev.vportable.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputEncoderTest {

    private fun List<ByteArray>.concat(): ByteArray {
        val out = ArrayList<Byte>()
        for (chunk in this) chunk.forEach { out.add(it) }
        return out.toByteArray()
    }

    @Test
    fun `plain text becomes a single utf8 chunk`() {
        val chunks = TerminalInputEncoder.encodeText("ls -la")
        assertEquals(1, chunks.size)
        assertEquals("ls -la", chunks[0].toString(Charsets.UTF_8))
    }

    @Test
    fun `newline is sent as a single carriage return`() {
        val chunks = TerminalInputEncoder.encodeText("echo hi\n")
        assertEquals(2, chunks.size)
        assertEquals("echo hi", chunks[0].toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x0D), chunks[1])
    }

    @Test
    fun `carriage return newline pair is not sent twice`() {
        val chunks = TerminalInputEncoder.encodeText("pwd\r\n")
        assertEquals(2, chunks.size)
        assertEquals("pwd", chunks[0].toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x0D), chunks[1])
    }

    @Test
    fun `enter alone produces exactly one carriage return`() {
        assertArrayEquals(byteArrayOf(0x0D), TerminalInputEncoder.encodeText("\n").single())
    }

    @Test
    fun `empty input produces no bytes`() {
        assertTrue(TerminalInputEncoder.encodeText("").isEmpty())
    }

    @Test
    fun `multi byte characters survive encoding`() {
        assertEquals("é", TerminalInputEncoder.encodeText("é").concat().toString(Charsets.UTF_8))
    }

    @Test
    fun `ctrl letter maps to its control byte`() {
        assertArrayEquals(byteArrayOf(0x01), TerminalInputEncoder.encodeCtrl('a'))
        assertArrayEquals(byteArrayOf(0x03), TerminalInputEncoder.encodeCtrl('C'))
        assertArrayEquals(byteArrayOf(0x1A), TerminalInputEncoder.encodeCtrl('z'))
    }

    @Test
    fun `ctrl on a non letter yields nothing`() {
        assertNull(TerminalInputEncoder.encodeCtrl('1'))
        assertNull(TerminalInputEncoder.encodeCtrl('/'))
    }

    @Test
    fun `control keys use the bytes a terminal expects`() {
        assertArrayEquals(byteArrayOf(0x0D), TerminalInputEncoder.ENTER)
        assertArrayEquals(byteArrayOf(0x7F), TerminalInputEncoder.BACKSPACE)
        assertArrayEquals(byteArrayOf(0x1B), TerminalInputEncoder.ESCAPE)
        assertArrayEquals(byteArrayOf(0x09), TerminalInputEncoder.TAB)
        assertArrayEquals(byteArrayOf(0x03), TerminalInputEncoder.CTRL_C)
        assertArrayEquals(byteArrayOf(0x1B, 0x5B, 0x41), TerminalInputEncoder.ARROW_UP)
    }
}
