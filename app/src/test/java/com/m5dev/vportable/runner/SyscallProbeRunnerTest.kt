package com.m5dev.vportable.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class SyscallProbeRunnerTest {

    @Test
    fun testExecuteSubprocess_successfulCommand() {
        val lines = mutableListOf<String>()
        val result = SyscallProbeRunner.executeSubprocess(listOf("echo", "Hello Gate 1")) { line ->
            lines.add(line)
        }
        assertEquals(0, result.exitCode)
        assertEquals("PASSED", result.failureBadge)
        assertEquals(1, lines.size)
        assertEquals("Hello Gate 1", lines[0])
    }

    @Test
    fun testExecuteSubprocess_nonExistentCommand() {
        val lines = mutableListOf<String>()
        val result = SyscallProbeRunner.executeSubprocess(listOf("/invalid_path/non_existent_binary")) { line ->
            lines.add(line)
        }
        assertEquals(-1, result.exitCode)
        assertEquals("FAILED: ENOENT", result.failureBadge)
    }
}
