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

    fun runGate1_5Probe(): Flow<ProbeEvent> = runGate1Probe()

    fun runGate1Probe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 1.5: Ptrace Syscall Interception & Path Rewriting (VFS PoC) ==="))

        val markerFile = File(context.filesDir, "vfs_marker.txt")
        markerFile.writeText("VFS_INTERCEPTION_VALIDATED")
        val targetPath = markerFile.absolutePath

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePtraceProbePath = "$nativeDir/libprobe_ptrace.so"

        emit(ProbeEvent.Log("[1/4] Preparing target test file and resolving native probe ($nativePtraceProbePath)..."))
        emit(ProbeEvent.Log("       Target file: $targetPath"))

        val ptraceBinary = File(nativePtraceProbePath)

        if (!ptraceBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Native probe missing in $nativeDir"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        emit(ProbeEvent.Log("[2/4] Native probe verified at $nativeDir"))
        emit(ProbeEvent.Log("       ptrace probe: ${ptraceBinary.name} (executable: ${ptraceBinary.canExecute()})"))

        val testCase = TestCase(
            name = "Native C Ptrace Syscall Interception & VFS Path Rewriting Probe (libprobe_ptrace.so)",
            command = listOf(nativePtraceProbePath, targetPath)
        )

        emit(ProbeEvent.Log("\n[3/4] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

        var rewriteSuccess = false
        var childReadSuccess = false

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("GATE_1_5_RESULT: VFS_REWRITE_SUCCESS")) {
                rewriteSuccess = true
            }
            if (logLine.contains("CHILD_READ: VFS_INTERCEPTION_VALIDATED")) {
                childReadSuccess = true
            }
        }

        val success = processResult.exitCode == 0 && rewriteSuccess && childReadSuccess

        if (success) {
            emit(ProbeEvent.Log("  -> Subprocess exited successfully / VFS path rewriting validated (SUCCESS)"))
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 1.5 Feasibility Gate Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: Syscall interception & path rewriting validated."))
            emit(ProbeEvent.Completed(true, "PASSED (GATE 1.5)", true))
        } else {
            val badge = if (processResult.exitCode != 0) processResult.failureBadge else "FAILED: VFS_REWRITE_FAILED"
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
                    0 -> "PASSED (GATE 1.5)"
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
