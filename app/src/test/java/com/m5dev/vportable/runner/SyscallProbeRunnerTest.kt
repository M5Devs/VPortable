package com.m5dev.vportable.runner

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyscallProbeRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testExecuteSubprocess_successfulCommand() {
        val lines = mutableListOf<String>()
        val result = SyscallProbeRunner.executeSubprocess(listOf("echo", "Hello Gate 2"), emptyMap()) { line ->
            lines.add(line)
        }
        assertEquals(0, result.exitCode)
        assertEquals("PASSED", result.failureBadge)
        assertEquals(1, lines.size)
        assertEquals("Hello Gate 2", lines[0])
    }

    @Test
    fun testExecuteSubprocess_nonExistentCommand() {
        val lines = mutableListOf<String>()
        val result = SyscallProbeRunner.executeSubprocess(listOf("/invalid_path/non_existent_binary"), emptyMap()) { line ->
            lines.add(line)
        }
        assertEquals(-1, result.exitCode)
        assertEquals("FAILED: ENOENT", result.failureBadge)
    }

    @Test
    fun testRunGate1_5Probe_missingNativeBinary() = runTest {
        val filesDir = tempFolder.newFolder("files")
        val nativeDir = tempFolder.newFolder("lib")

        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeDir.absolutePath
        }

        val mockContext = mock<Context>()
        whenever(mockContext.filesDir).thenReturn(filesDir)
        whenever(mockContext.applicationInfo).thenReturn(appInfo)

        val runner = SyscallProbeRunner(mockContext)
        val events = runner.runGate1_5Probe().toList()

        val completedEvent = events.last() as ProbeEvent.Completed
        assertFalse(completedEvent.success)
        assertEquals("FAILED: ENOENT", completedEvent.badgeText)

        val markerFile = java.io.File(filesDir, "vfs_marker.txt")
        assertTrue(markerFile.exists())
        assertEquals("VFS_INTERCEPTION_VALIDATED", markerFile.readText())
    }

    @Test
    fun testRunGate2Probe_missingNativeBinary() = runTest {
        val filesDir = tempFolder.newFolder("files")
        val nativeDir = tempFolder.newFolder("lib")

        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeDir.absolutePath
        }

        val mockContext = mock<Context>()
        whenever(mockContext.filesDir).thenReturn(filesDir)
        whenever(mockContext.applicationInfo).thenReturn(appInfo)

        val runner = SyscallProbeRunner(mockContext)
        val events = runner.runGate2Probe().toList()

        val completedEvent = events.last() as ProbeEvent.Completed
        assertFalse(completedEvent.success)
        assertEquals("FAILED: ENOENT", completedEvent.badgeText)

        val rootfsDir = java.io.File(filesDir, "rootfs")
        val binSh = java.io.File(rootfsDir, "bin/sh")
        assertTrue(binSh.exists())
    }

    @Test
    fun testRunGate3_1Probe_missingNativeBinary() = runTest {
        val filesDir = tempFolder.newFolder("files_g3_1")
        val nativeDir = tempFolder.newFolder("lib_g3_1")

        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeDir.absolutePath
        }

        val mockContext = mock<Context>()
        whenever(mockContext.filesDir).thenReturn(filesDir)
        whenever(mockContext.applicationInfo).thenReturn(appInfo)

        val runner = SyscallProbeRunner(mockContext)
        val events = runner.runGate3_1Probe().toList()

        val completedEvent = events.last() as ProbeEvent.Completed
        assertFalse(completedEvent.success)
        assertEquals("FAILED: ENOENT", completedEvent.badgeText)

        val rootfsDir = java.io.File(filesDir, "rootfs")
        val binDir = java.io.File(rootfsDir, "bin")

        assertTrue(binDir.exists())
    }

    @Test
    fun testRunGate4Probe_missingNativeBinary() = runTest {
        val filesDir = tempFolder.newFolder("files_g4")
        val nativeDir = tempFolder.newFolder("lib_g4")

        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeDir.absolutePath
        }

        val mockContext = mock<Context>()
        whenever(mockContext.filesDir).thenReturn(filesDir)
        whenever(mockContext.applicationInfo).thenReturn(appInfo)

        val runner = SyscallProbeRunner(mockContext)
        val events = runner.runGate4Probe().toList()

        val completedEvent = events.last() as ProbeEvent.Completed
        assertFalse(completedEvent.success)
        assertEquals("FAILED: ENOENT", completedEvent.badgeText)

        val rootfsDir = java.io.File(filesDir, "rootfs")
        val libDir = java.io.File(rootfsDir, "lib")

        assertTrue(libDir.exists())
    }

    @Test
    fun testRunGate5Probe_missingNativeBinary() = runTest {
        val filesDir = tempFolder.newFolder("files_g5")
        val nativeDir = tempFolder.newFolder("lib_g5")

        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeDir.absolutePath
        }

        val mockContext = mock<Context>()
        whenever(mockContext.filesDir).thenReturn(filesDir)
        whenever(mockContext.applicationInfo).thenReturn(appInfo)

        val runner = SyscallProbeRunner(mockContext)
        val events = runner.runGate5Probe().toList()

        val completedEvent = events.last() as ProbeEvent.Completed
        assertFalse(completedEvent.success)
        assertEquals("FAILED: ENOENT", completedEvent.badgeText)

        val rootfsDir = java.io.File(filesDir, "rootfs")
        val binDir = java.io.File(rootfsDir, "bin")
        val libDir = java.io.File(rootfsDir, "lib")

        assertTrue(binDir.exists())
        assertTrue(libDir.exists())
    }
}
