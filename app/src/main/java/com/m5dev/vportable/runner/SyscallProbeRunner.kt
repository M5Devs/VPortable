package com.m5dev.vportable.runner

import android.content.Context
import com.m5dev.vportable.util.BinaryExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

sealed class ProbeEvent {
    data class Log(val line: String) : ProbeEvent()
    data class Completed(val success: Boolean, val badgeText: String, val badgeColorGreen: Boolean) : ProbeEvent()
}

class SyscallProbeRunner(private val context: Context) {

    fun runGate1Probe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 1: Minimal PRoot & Syscall Feasibility Test ==="))

        val extractor = BinaryExtractor(context)
        emit(ProbeEvent.Log("[1/4] Resolving binaries (proot, busybox)..."))

        val resolveResult = extractor.resolveOrExtractBinaries()
        if (resolveResult.isFailure) {
            val err = resolveResult.exceptionOrNull()
            emit(ProbeEvent.Log("ERROR: Binary resolution failed: ${err?.message}"))
            emit(ProbeEvent.Completed(false, "FAILED: EXTRACTION_ERROR", false))
            return@flow
        }

        val binDir = resolveResult.getOrThrow()

        val prootBinary = if (File(binDir, "libproot.so").exists()) File(binDir, "libproot.so") else File(binDir, "proot")
        val busyboxBinary = if (File(binDir, "libbusybox.so").exists()) File(binDir, "libbusybox.so") else File(binDir, "busybox")

        if (!prootBinary.exists() || !busyboxBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Binaries missing in ${binDir.absolutePath}"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        emit(ProbeEvent.Log("[2/4] Binaries verified at ${binDir.absolutePath}"))
        emit(ProbeEvent.Log("       proot binary: ${prootBinary.name} (executable: ${prootBinary.canExecute()})"))
        emit(ProbeEvent.Log("       busybox binary: ${busyboxBinary.name} (executable: ${busyboxBinary.canExecute()})"))

        val testCases = listOf(
            TestCase(
                name = "PRoot Kernel Interception (uname -a)",
                command = listOf(
                    prootBinary.absolutePath,
                    busyboxBinary.absolutePath,
                    "uname", "-a"
                )
            ),
            TestCase(
                name = "PRoot Syscall & User Context Probe (id)",
                command = listOf(
                    prootBinary.absolutePath,
                    busyboxBinary.absolutePath,
                    "id"
                )
            )
        )

        var overallSuccess = true
        var failureReason: String? = null

        for ((index, testCase) in testCases.withIndex()) {
            emit(ProbeEvent.Log("\n[3/4.${index + 1}] Executing: ${testCase.command.joinToString(" ")}"))

            val processResult = executeSubprocessSuspend(testCase.command) { logLine ->
                emit(ProbeEvent.Log("  $logLine"))
            }

            if (processResult.exitCode == 0) {
                emit(ProbeEvent.Log("  -> Subprocess exited with code 0 (SUCCESS)"))
            } else {
                overallSuccess = false
                val reason = processResult.failureBadge
                emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
                if (failureReason == null) {
                    failureReason = reason
                }
            }
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 1 Feasibility Gate Results..."))
        if (overallSuccess) {
            emit(ProbeEvent.Log("SUCCESS: PRoot ptrace & syscall interception validated on host SELinux domain."))
            emit(ProbeEvent.Completed(true, "PASSED", true))
        } else {
            val badge = failureReason ?: "FAILED: UNKNOWN"
            emit(ProbeEvent.Log("FAILURE: $badge"))
            emit(ProbeEvent.Completed(false, badge, false))
        }
    }.flowOn(Dispatchers.IO)

    internal data class TestCase(val name: String, val command: List<String>)

    internal data class ProcessOutput(val exitCode: Int, val failureBadge: String)

    companion object {
        internal suspend fun executeSubprocessSuspend(
            command: List<String>,
            onLine: suspend (String) -> Unit
        ): ProcessOutput {
            return try {
                val pb = ProcessBuilder(command)
                pb.redirectErrorStream(true)
                val process = pb.start()

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { onLine(it) }
                    }
                }

                val exitCode = process.waitFor()
                val badge = when (exitCode) {
                    0 -> "PASSED"
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_$exitCode"
                }
                ProcessOutput(exitCode, badge)
            } catch (e: SecurityException) {
                onLine("SecurityException during execution: ${e.message}")
                ProcessOutput(-1, "FAILED: PTRACE_DENIED")
            } catch (e: Exception) {
                onLine("Exception during execution: ${e.javaClass.simpleName} - ${e.message}")
                val badge = if (e.message?.contains("No such file", ignoreCase = true) == true) {
                    "FAILED: ENOENT"
                } else if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                    "FAILED: EPERM"
                } else {
                    "FAILED: ${e.javaClass.simpleName}"
                }
                ProcessOutput(-1, badge)
            }
        }

        internal fun executeSubprocess(
            command: List<String>,
            onLine: (String) -> Unit
        ): ProcessOutput {
            return try {
                val pb = ProcessBuilder(command)
                pb.redirectErrorStream(true)
                val process = pb.start()

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { onLine(it) }
                    }
                }

                val exitCode = process.waitFor()
                val badge = when (exitCode) {
                    0 -> "PASSED"
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_$exitCode"
                }
                ProcessOutput(exitCode, badge)
            } catch (e: SecurityException) {
                onLine("SecurityException during execution: ${e.message}")
                ProcessOutput(-1, "FAILED: PTRACE_DENIED")
            } catch (e: Exception) {
                onLine("Exception during execution: ${e.javaClass.simpleName} - ${e.message}")
                val badge = if (e.message?.contains("No such file", ignoreCase = true) == true) {
                    "FAILED: ENOENT"
                } else if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                    "FAILED: EPERM"
                } else {
                    "FAILED: ${e.javaClass.simpleName}"
                }
                ProcessOutput(-1, badge)
            }
        }
    }
}
