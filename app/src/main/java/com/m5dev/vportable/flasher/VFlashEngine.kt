package com.m5dev.vportable.flasher

import android.content.Context
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * V-Flash Engine: Handles extraction, symlink restoration, security validation,
 * and permissions setup for container rootfs archives (e.g. Alpine mini-rootfs).
 */
object VFlashEngine {

    const val DEFAULT_ALPINE_ASSET = "rootfs/alpine-minirootfs-x86_64.tar.gz"

    /**
     * Extracts an Alpine (or generic tar.gz) rootfs archive directly into context.filesDir/rootfs.
     */
    fun extractAlpineRootfs(
        context: Context,
        assetPath: String = DEFAULT_ALPINE_ASSET,
        targetDir: File = File(context.filesDir, "rootfs"),
        onLog: ((String) -> Unit)? = null
    ): Boolean {
        try {
            onLog?.invoke("Preparing target directory: ${targetDir.absolutePath}")
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            if (!targetDir.mkdirs()) {
                onLog?.invoke("ERROR: Failed to create target directory")
                return false
            }

            onLog?.invoke("Opening asset: $assetPath")
            context.assets.open(assetPath).use { assetStream ->
                extractTarGzStream(assetStream, targetDir, onLog)
            }

            onLog?.invoke("Enforcing executable permissions on /bin, /sbin, /usr/bin, /usr/sbin...")
            setExecutablePermissions(targetDir)

            val hasBusybox = File(targetDir, "bin/busybox").exists()
            val hasOsRelease = File(targetDir, "etc/os-release").exists()

            onLog?.invoke("Verification check: bin/busybox exists=$hasBusybox, etc/os-release exists=$hasOsRelease")
            return hasBusybox && hasOsRelease
        } catch (e: Exception) {
            onLog?.invoke("ERROR during rootfs extraction: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Extracts a .tar.gz stream to the specified destination directory directly.
     */
    fun extractTarGzStream(
        inputStream: InputStream,
        destDir: File,
        onLog: ((String) -> Unit)? = null
    ) {
        val gzipStream = GZIPInputStream(BufferedInputStream(inputStream))
        val tarReader = TarInputStream(gzipStream)

        var entryCount = 0
        var symlinkCount = 0

        var entry = tarReader.readNextEntry()
        while (entry != null) {
            // Security: Path traversal check
            if (entry.name.startsWith("/") || entry.name.contains("..")) {
                throw SecurityException("Malicious path traversal entry: ${entry.name}")
            }

            val entryPath = entry.name.removePrefix("./").removePrefix("/")
            if (entryPath.isEmpty() || entryPath == ".") {
                entry = tarReader.readNextEntry()
                continue
            }

            val outFile = File(destDir, entryPath)

            when {
                entry.isSymbolicLink -> {
                    outFile.parentFile?.mkdirs()
                    if (outFile.exists() || isSymlink(outFile)) {
                        outFile.delete()
                    }
                    val target = entry.linkName
                    try {
                        Os.symlink(target, outFile.absolutePath)
                        symlinkCount++
                    } catch (e: Exception) {
                        onLog?.invoke("Failed symlink ${outFile.name} -> $target: ${e.message}")
                    }
                }
                entry.isDirectory -> {
                    outFile.mkdirs()
                }
                entry.isRegularFile -> {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        tarReader.copyCurrentEntryData(fos)
                    }
                    if ((entry.mode and 73) != 0) { // 0111 octal
                        outFile.setExecutable(true, false)
                    }
                }
            }

            entryCount++
            entry = tarReader.readNextEntry()
        }

        onLog?.invoke("Extracted $entryCount entries ($symlinkCount symlinks recreated)")
    }

    /**
     * Sets executable permission (0755) on all binaries in standard Unix binary directories.
     */
    fun setExecutablePermissions(rootfsDir: File) {
        val binDirs = listOf(
            File(rootfsDir, "bin"),
            File(rootfsDir, "sbin"),
            File(rootfsDir, "usr/bin"),
            File(rootfsDir, "usr/sbin")
        )

        for (dir in binDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && !isSymlink(file)) {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                    }
                }
            }
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            val parentFile = file.parentFile ?: return false
            val canon = File(parentFile.canonicalFile, file.name)
            !canon.canonicalFile.equals(canon.absoluteFile)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Internal TAR Header and Stream Parser for POSIX / USTAR / PAX archives.
     */
    internal class TarEntry(
        val name: String,
        val mode: Int,
        val size: Long,
        val typeFlag: Byte,
        val linkName: String
    ) {
        val isDirectory: Boolean get() = typeFlag == '5'.code.toByte() || name.endsWith("/")
        val isSymbolicLink: Boolean get() = typeFlag == '2'.code.toByte()
        val isRegularFile: Boolean get() = typeFlag == '0'.code.toByte() || typeFlag == 0.toByte()
    }

    internal class TarInputStream(private val stream: InputStream) {

        private var currentEntryBytesRead = 0L
        private var currentEntrySize = 0L
        private var paxLongName: String? = null
        private var paxLongLink: String? = null

        fun readNextEntry(): TarEntry? {
            if (currentEntrySize > currentEntryBytesRead) {
                val remaining = currentEntrySize - currentEntryBytesRead
                val paddedRemaining = (remaining + 511L) and 511L.inv()
                skipBytesFully(stream, paddedRemaining)
            }

            paxLongName = null
            paxLongLink = null

            while (true) {
                val header = ByteArray(512)
                if (!readFully(stream, header)) {
                    return null
                }

                if (header.all { it == 0.toByte() }) {
                    return null
                }

                val nameRaw = parseString(header, 0, 100)
                val mode = parseOctal(header, 100, 8).toInt()
                val size = parseOctal(header, 124, 12)
                val typeFlag = header[156]
                val linkNameRaw = parseString(header, 157, 100)
                val magic = parseString(header, 257, 6)
                val prefix = if (magic.startsWith("ustar")) parseString(header, 345, 155) else ""

                var entryName = if (prefix.isNotEmpty()) "$prefix/$nameRaw" else nameRaw
                var linkName = linkNameRaw

                if (typeFlag == 'L'.code.toByte() || typeFlag == 'K'.code.toByte() || typeFlag == 'x'.code.toByte()) {
                    val longData = ByteArray(size.toInt())
                    readFully(stream, longData)
                    val pad = (size + 511L) and 511L.inv()
                    val skipAmount = pad - size
                    if (skipAmount > 0) skipBytesFully(stream, skipAmount)

                    val longStr = String(longData, Charsets.UTF_8).trimEnd('\u0000', '\n', '\r')
                    if (typeFlag == 'L'.code.toByte()) {
                        paxLongName = longStr
                    } else if (typeFlag == 'K'.code.toByte()) {
                        paxLongLink = linkNameRaw.ifEmpty { longStr }
                        if (paxLongName == null) paxLongName = longStr
                    }
                    continue
                }

                if (paxLongName != null) {
                    entryName = paxLongName!!
                }
                if (paxLongLink != null) {
                    linkName = paxLongLink!!
                }

                currentEntryBytesRead = 0L
                currentEntrySize = size

                return TarEntry(
                    name = entryName,
                    mode = mode,
                    size = size,
                    typeFlag = typeFlag,
                    linkName = linkName
                )
            }
        }

        fun copyCurrentEntryData(out: FileOutputStream) {
            val buffer = ByteArray(8192)
            var remaining = currentEntrySize - currentEntryBytesRead
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = stream.read(buffer, 0, toRead)
                if (bytesRead <= 0) break
                out.write(buffer, 0, bytesRead)
                currentEntryBytesRead += bytesRead
                remaining -= bytesRead
            }

            val pad = (currentEntrySize + 511L) and 511L.inv()
            val extraToSkip = pad - currentEntrySize
            if (extraToSkip > 0) {
                skipBytesFully(stream, extraToSkip)
            }
        }

        private fun parseString(buf: ByteArray, offset: Int, length: Int): String {
            var end = offset
            while (end < offset + length && buf[end] != 0.toByte()) {
                end++
            }
            return String(buf, offset, end - offset, Charsets.UTF_8)
        }

        private fun parseOctal(buf: ByteArray, offset: Int, length: Int): Long {
            var result = 0L
            var start = offset
            val end = offset + length
            while (start < end && (buf[start] == ' '.code.toByte() || buf[start] == 0.toByte())) {
                start++
            }
            while (start < end) {
                val b = buf[start]
                if (b < '0'.code.toByte() || b > '7'.code.toByte()) break
                result = (result shl 3) + (b - '0'.code.toByte())
                start++
            }
            return result
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var got = 0
            while (got < buf.size) {
                val count = input.read(buf, got, buf.size - got)
                if (count < 0) return got > 0
                got += count
            }
            return true
        }

        private fun skipBytesFully(input: InputStream, amount: Long) {
            var remaining = amount
            val buf = ByteArray(minOf(4096L, remaining).toInt())
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                remaining -= read
            }
        }
    }
}
