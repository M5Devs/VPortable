package com.m5dev.vportable.runner

import android.content.Context
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

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val prootPath = "$nativeDir/libproot.so"
        val busyboxPath = "$nativeDir/libbusybox.so"

        emit(ProbeEvent.Log("[1/4] Resolving binaries ($prootPath, $busyboxPath)..."))

        val prootBinary = File(prootPath)
        val busyboxBinary = File(busyboxPath)

        if (!prootBinary.exists() || !busyboxBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Binaries missing in $nativeDir"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        emit(ProbeEvent.Log("[2/4] Binaries verified at $nativeDir"))
        emit(ProbeEvent.Log("       proot binary: ${prootBinary.name} (executable: ${prootBinary.canExecute()})"))
        emit(ProbeEvent.Log("       busybox binary: ${busyboxBinary.name} (executable: ${busyboxBinary.canExecute()})"))

        val testCases = listOf(
            TestCase(
                name = "Standalone Binary Execution Probe (busybox uname -a)",
                command = listOf(
                    busyboxPath,
                    "busybox",
                    "uname", "-a"
                ),
                env = mapOf("PROOT_NO_SECCOMP" to "1")
            ),
            TestCase(
                name = "PRoot Kernel Interception Probe (proot busybox uname -a)",
                command = listOf(
                    prootPath,
                    busyboxPath,
                    "busybox",
                    "uname", "-a"
                ),
                env = mapOf("PROOT_NO_SECCOMP" to "1")
            ),
            TestCase(
                name = "PRoot Syscall & User Context Probe (proot busybox id)",
                command = listOf(
                    prootPath,
                    busyboxPath,
                    "busybox",
                    "id"
                ),
                env = mapOf("PROOT_NO_SECCOMP" to "1")
            )
        )

        var overallSuccess = true
        var failureReason: String? = null

        for ((index, testCase) in testCases.withIndex()) {
            emit(ProbeEvent.Log("\n[3/4.${index + 1}] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

            val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
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

    internal data class TestCase(
        val name: String,
        val command: List<String>,
        val env: Map<String, String> = emptyMap()
    )

    internal data class ProcessOutput(val exitCode: Int, val failureBadge: String)

    companion object {
        internal suspend fun executeSubprocessSuspend(
            command: List<String>,
            env: Map<String, String> = emptyMap(),
            onLine: suspend (String) -> Unit
        ): ProcessOutput {
            return try {
                val pb = ProcessBuilder(command)
                pb.redirectErrorStream(true)
                if (env.isNotEmpty()) {
                    pb.environment().putAll(env)
                }
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

        @JvmOverloads
        internal fun executeSubprocess(
            command: List<String>,
            env: Map<String, String> = emptyMap(),
            onLine: (String) -> Unit
        ): ProcessOutput {
            return try {
                val pb = ProcessBuilder(command)
                pb.redirectErrorStream(true)
                if (env.isNotEmpty()) {
                    pb.environment().putAll(env)
                }
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
