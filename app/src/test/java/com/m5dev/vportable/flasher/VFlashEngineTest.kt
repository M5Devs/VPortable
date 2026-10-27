package com.m5dev.vportable.flasher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class VFlashEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testExtractTarGzStream_simpleFileAndDir() {
        val destDir = tempFolder.newFolder("rootfs_test")

        // Build a minimal TAR in memory
        val tarBytes = createMockTarGz(
            listOf(
                MockTarEntry(name = "bin/", isDir = true),
                MockTarEntry(name = "bin/hello", content = "echo Hello World\n", mode = 493) // 0755
            )
        )

        VFlashEngine.extractTarGzStream(ByteArrayInputStream(tarBytes), destDir)

        val helloFile = File(destDir, "bin/hello")
        assertTrue(helloFile.exists())
        assertEquals("echo Hello World\n", helloFile.readText())
        assertTrue(helloFile.canExecute())
    }

    @Test(expected = SecurityException::class)
    fun testExtractTarGzStream_rejectPathTraversal() {
        val destDir = tempFolder.newFolder("rootfs_traversal")

        val tarBytes = createMockTarGz(
            listOf(
                MockTarEntry(name = "../evil.txt", content = "malicious payload")
            )
        )

        VFlashEngine.extractTarGzStream(ByteArrayInputStream(tarBytes), destDir)
    }

    @Test
    fun testSetExecutablePermissions() {
        val rootfsDir = tempFolder.newFolder("rootfs_perms")
        val binDir = File(rootfsDir, "bin").apply { mkdirs() }
        val testBin = File(binDir, "sh").apply { writeText("#!/bin/sh\n") }

        testBin.setExecutable(false)
        assertFalse(testBin.canExecute())

        VFlashEngine.setExecutablePermissions(rootfsDir)

        assertTrue(testBin.canExecute())
    }

    private data class MockTarEntry(
        val name: String,
        val content: String = "",
        val isDir: Boolean = false,
        val mode: Int = 420 // 0644
    )

    private fun createMockTarGz(entries: List<MockTarEntry>): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            for (entry in entries) {
                val header = ByteArray(512)
                val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

                val modeOctal = String.format("%07o", entry.mode).toByteArray()
                System.arraycopy(modeOctal, 0, header, 100, 7)

                val contentBytes = entry.content.toByteArray(Charsets.UTF_8)
                val size = if (entry.isDir) 0 else contentBytes.size
                val sizeOctal = String.format("%011o", size).toByteArray()
                System.arraycopy(sizeOctal, 0, header, 124, 11)

                header[156] = if (entry.isDir) '5'.code.toByte() else '0'.code.toByte()

                // "ustar" magic
                val magic = "ustar\u000000".toByteArray()
                System.arraycopy(magic, 0, header, 257, magic.size)

                // Simple header checksum
                for (i in 148..155) header[i] = ' '.code.toByte()
                var chk = 0
                for (b in header) chk += (b.toInt() and 0xFF)
                val chkOctal = String.format("%06o\u0000 ", chk).toByteArray()
                System.arraycopy(chkOctal, 0, header, 148, minOf(chkOctal.size, 8))

                gzos.write(header)

                if (!entry.isDir && contentBytes.isNotEmpty()) {
                    gzos.write(contentBytes)
                    val pad = (contentBytes.size + 511) and 511.inv()
                    val extra = pad - contentBytes.size
                    if (extra > 0) gzos.write(ByteArray(extra))
                }
            }
            // 2 EOF blocks
            gzos.write(ByteArray(1024))
        }
        return baos.toByteArray()
    }
}
