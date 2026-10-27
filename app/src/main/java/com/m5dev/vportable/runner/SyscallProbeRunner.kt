package com.m5dev.vportable.runner

import android.content.Context
import com.m5dev.vportable.flasher.VFlashEngine
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

        var redirectedLogFound = false
        var childReadSuccess = false

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("Redirected openat")) redirectedLogFound = true
            if (logLine.contains("CHILD_READ: VFS_INTERCEPTION_VALIDATED")) childReadSuccess = true
        }

        val success = processResult.exitCode == 0 && redirectedLogFound && childReadSuccess

        if (success) {
            emit(ProbeEvent.Log("  -> ptrace syscall interception & path rewriting succeeded (SUCCESS)"))
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 1.5 Feasibility Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: Native Ptrace Syscall Interception & VFS Path Rewriting validated."))
            emit(ProbeEvent.Completed(true, "PASSED (GATE 1.5)", true))
        } else {
            val badge = if (processResult.exitCode != 0) {
                when (processResult.exitCode) {
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_${processResult.exitCode}"
                }
            } else {
                "FAILED: VFS_INTERCEPTION_FAILED"
            }
            emit(ProbeEvent.Log("FAILURE: $badge"))
            emit(ProbeEvent.Completed(false, badge, false))
        }
    }.flowOn(Dispatchers.IO)

    fun runGate2Probe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 2: PRoot Subprocess Supervision & Rootfs Execution ==="))

        val rootfsDir = File(context.filesDir, "rootfs")
        val binDir = File(rootfsDir, "bin")
        binDir.mkdirs()

        val binSh = File(binDir, "sh")
        if (!binSh.exists()) {
            binSh.writeText("#!/system/bin/sh\necho VFS_ROOTFS_EXEC_SUCCESS\n")
            binSh.setExecutable(true)
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePtraceProbePath = "$nativeDir/libprobe_ptrace.so"
        val nativeBusyboxPath = "$nativeDir/libbusybox.so"

        emit(ProbeEvent.Log("[1/4] Staging virtual rootfs structure in ${rootfsDir.absolutePath}..."))
        emit(ProbeEvent.Log("       Rootfs bin: ${binDir.absolutePath}"))
        emit(ProbeEvent.Log("       Staged script: ${binSh.absolutePath}"))

        val ptraceBinary = File(nativePtraceProbePath)
        val busyboxBinary = File(nativeBusyboxPath)

        if (!ptraceBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Native supervisor missing in $nativeDir ($nativePtraceProbePath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        if (!busyboxBinary.exists()) {
            emit(ProbeEvent.Log("WARNING: Busybox binary missing in $nativeDir ($nativeBusyboxPath)"))
        }

        emit(ProbeEvent.Log("[2/4] Native binaries verified at $nativeDir"))
        emit(ProbeEvent.Log("       Supervisor: ${ptraceBinary.name} (canExecute: ${ptraceBinary.canExecute()})"))

        val env = mapOf("PROOT_NO_SECCOMP" to "1")
        val command = listOf(nativePtraceProbePath, rootfsDir.absolutePath, nativeDir)

        val testCase = TestCase(
            name = "Gate 2 PRoot Supervisor & Rootfs Execution Probe",
            command = command,
            env = env
        )

        emit(ProbeEvent.Log("\n[3/4] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

        var execveRedirectFound = false
        var rootfsExecSuccess = false

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("Redirected execve /bin/sh")) execveRedirectFound = true
            if (logLine.contains("VFS_ROOTFS_EXEC_SUCCESS")) rootfsExecSuccess = true
        }

        val success = processResult.exitCode == 0 && (execveRedirectFound || rootfsExecSuccess)

        if (success) {
            emit(ProbeEvent.Log("  -> Rootfs execution & execve redirection verified (SUCCESS)"))
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 2 Feasibility Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: PRoot supervisor & VFS rootfs execution validated."))
            emit(ProbeEvent.Completed(true, "PASSED (GATE 2)", true))
        } else {
            val badge = if (processResult.exitCode != 0) {
                when (processResult.exitCode) {
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_${processResult.exitCode}"
                }
            } else {
                "FAILED: VFS_EXEC_FAILED"
            }
            emit(ProbeEvent.Log("FAILURE: $badge"))
            emit(ProbeEvent.Completed(false, badge, false))
        }
    }.flowOn(Dispatchers.IO)

    fun runGate3_1Probe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 3.1: Multi-Process Supervision & Tree Tracking ==="))

        val rootfsDir = File(context.filesDir, "rootfs")
        val binDir = File(rootfsDir, "bin")
        binDir.mkdirs()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePtraceProbePath = "$nativeDir/libprobe_ptrace.so"
        val nativeBusyboxPath = "$nativeDir/libbusybox.so"

        emit(ProbeEvent.Log("[1/4] Staging virtual rootfs structure in ${rootfsDir.absolutePath}..."))

        val ptraceBinary = File(nativePtraceProbePath)
        val busyboxBinary = File(nativeBusyboxPath)

        if (!ptraceBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Native supervisor missing in $nativeDir ($nativePtraceProbePath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        if (!busyboxBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: BusyBox missing in $nativeDir ($nativeBusyboxPath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        emit(ProbeEvent.Log("[2/4] Native binaries verified at $nativeDir"))
        emit(ProbeEvent.Log("       Supervisor: ${ptraceBinary.name} (canExecute: ${ptraceBinary.canExecute()})"))
        emit(ProbeEvent.Log("       BusyBox: ${busyboxBinary.name} (canExecute: ${busyboxBinary.canExecute()})"))

        val targetCmd = "sh -c \"echo GATE_3_1_CHILD_1; sh -c 'echo GATE_3_1_CHILD_2'\""
        val env = mapOf("PROOT_NO_SECCOMP" to "1")
        val command = listOf(nativePtraceProbePath, rootfsDir.absolutePath, nativeDir, targetCmd)

        val testCase = TestCase(
            name = "Gate 3.1 Multi-Process Tree Tracking Probe",
            command = command,
            env = env
        )

        emit(ProbeEvent.Log("\n[3/4] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

        var child1Found = false
        var child2Found = false
        val detectedPids = mutableSetOf<String>()

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("GATE_3_1_CHILD_1")) child1Found = true
            if (logLine.contains("GATE_3_1_CHILD_2")) child2Found = true

            val pidMatch = Regex("""Attached to child PID=(\d+)""").find(logLine)
            pidMatch?.groupValues?.get(1)?.let { pid ->
                detectedPids.add(pid)
            }
        }

        val success = processResult.exitCode == 0 && child1Found && child2Found

        if (success) {
            emit(ProbeEvent.Log("  -> Multi-process tree tracking verified across ${detectedPids.size + 1} process nodes (SUCCESS)"))
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 3.1 Feasibility Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: Multi-process tree tracking & attach handling validated."))
            emit(ProbeEvent.Completed(true, "PASSED (GATE 3.1)", true))
        } else {
            val badge = if (processResult.exitCode != 0) {
                when (processResult.exitCode) {
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_${processResult.exitCode}"
                }
            } else {
                "FAILED: MULTI_PROC_TRACK_FAILED"
            }
            emit(ProbeEvent.Log("FAILURE: $badge"))
            emit(ProbeEvent.Completed(false, badge, false))
        }
    }.flowOn(Dispatchers.IO)

    fun runGate3_2Probe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 3.2: Per-Process State Tracking (CWD & Mini-Rootfs) ==="))

        val rootfsDir = File(context.filesDir, "rootfs")
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()

        val hostnameFile = File(etcDir, "hostname")
        hostnameFile.writeText("vportable-node1\n")

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePtraceProbePath = "$nativeDir/libprobe_ptrace.so"
        val nativeBusyboxPath = "$nativeDir/libbusybox.so"

        emit(ProbeEvent.Log("[1/4] Staging virtual rootfs structure in ${rootfsDir.absolutePath}..."))
        emit(ProbeEvent.Log("       Rootfs etc: ${etcDir.absolutePath}"))
        emit(ProbeEvent.Log("       Hostname file: ${hostnameFile.absolutePath}"))

        val ptraceBinary = File(nativePtraceProbePath)
        val busyboxBinary = File(nativeBusyboxPath)

        if (!ptraceBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Native supervisor missing in $nativeDir ($nativePtraceProbePath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        if (!busyboxBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: BusyBox missing in $nativeDir ($nativeBusyboxPath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        emit(ProbeEvent.Log("[2/4] Native binaries verified at $nativeDir"))
        emit(ProbeEvent.Log("       Supervisor: ${ptraceBinary.name} (canExecute: ${ptraceBinary.canExecute()})"))
        emit(ProbeEvent.Log("       BusyBox: ${busyboxBinary.name} (canExecute: ${busyboxBinary.canExecute()})"))

        val targetCmd = "cd /etc && cat hostname"
        val env = mapOf("PROOT_NO_SECCOMP" to "1")
        val command = listOf(nativePtraceProbePath, rootfsDir.absolutePath, nativeDir, targetCmd)

        val testCase = TestCase(
            name = "Gate 3.2 CWD & Rootfs Probe",
            command = command,
            env = env
        )

        emit(ProbeEvent.Log("\n[3/4] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

        var shellSuccessOutput = false
        val detectedPids = mutableSetOf<String>()

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("vportable-node1") || logLine.contains("GATE_3_2_RESULT: CWD_ROOTFS_SUCCESS")) {
                shellSuccessOutput = true
            }
            val pidMatch = Regex("""PID[= ](\d+)""").find(logLine)
            pidMatch?.groupValues?.get(1)?.let { pid ->
                detectedPids.add(pid)
            }
        }

        val success = processResult.exitCode == 0 && shellSuccessOutput

        if (success) {
            emit(ProbeEvent.Log("  -> CWD traversal & file read successfully verified (SUCCESS)"))
            emit(ProbeEvent.Log("  -> Interception confirmed across ${detectedPids.size} PIDs: ${detectedPids.joinToString(", ")}"))
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 3.2 Feasibility Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: Multi-process CWD tracking & VFS mini-rootfs validated."))
            emit(ProbeEvent.Completed(true, "PASSED (GATE 3.2)", true))
        } else {
            val badge = if (processResult.exitCode != 0) {
                when (processResult.exitCode) {
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_${processResult.exitCode}"
                }
            } else {
                "FAILED: VFS_GATE_3_2_FAILED"
            }
            emit(ProbeEvent.Log("FAILURE: $badge"))
            emit(ProbeEvent.Completed(false, badge, false))
        }
    }.flowOn(Dispatchers.IO)

    fun runGate4Probe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 4: Dynamic Linking & Shared Library VFS Redirection (/lib) ==="))

        val rootfsDir = File(context.filesDir, "rootfs")
        val libDir = File(rootfsDir, "lib")
        val lib64Dir = File(rootfsDir, "lib64")
        libDir.mkdirs()
        lib64Dir.mkdirs()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePtraceProbePath = "$nativeDir/libprobe_ptrace.so"
        val nativeVfsSharedPath = "$nativeDir/libvfs_shared.so"

        val targetSharedLib = File(libDir, "libvfs_shared.so")
        val sourceSharedLib = File(nativeVfsSharedPath)

        if (sourceSharedLib.exists()) {
            val t1 = File(libDir, "libvfs_shared.so")
            val t2 = File(lib64Dir, "libvfs_shared.so")

            sourceSharedLib.copyTo(t1, overwrite = true)
            sourceSharedLib.copyTo(t2, overwrite = true)

            t1.setReadable(true, false)
            t2.setReadable(true, false)

            emit(ProbeEvent.Log("Staged libvfs_shared.so verified: ${t1.absolutePath} (exists: ${t1.exists()}, size: ${t1.length()} bytes)"))
        } else {
            emit(ProbeEvent.Log("WARNING: Source shared library missing at $nativeVfsSharedPath"))
        }

        emit(ProbeEvent.Log("[1/4] Staging virtual rootfs structure in ${rootfsDir.absolutePath}..."))
        emit(ProbeEvent.Log("       Rootfs lib: ${libDir.absolutePath}"))
        emit(ProbeEvent.Log("       Staged library: ${targetSharedLib.absolutePath}"))

        val ptraceBinary = File(nativePtraceProbePath)

        if (!ptraceBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Native supervisor missing in $nativeDir ($nativePtraceProbePath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        if (!targetSharedLib.exists()) {
            emit(ProbeEvent.Log("ERROR: Staged shared library missing at ${targetSharedLib.absolutePath}"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        emit(ProbeEvent.Log("[2/4] Native binaries verified at $nativeDir"))
        emit(ProbeEvent.Log("       Supervisor: ${ptraceBinary.name} (canExecute: ${ptraceBinary.canExecute()})"))
        emit(ProbeEvent.Log("       Shared Lib: ${targetSharedLib.name}"))

        val env = mapOf("PROOT_NO_SECCOMP" to "1")
        val command = listOf(nativePtraceProbePath, rootfsDir.absolutePath, nativeDir)

        val testCase = TestCase(
            name = "Gate 4 Dynamic Linking Probe",
            command = command,
            env = env
        )

        emit(ProbeEvent.Log("\n[3/4] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

        var dynamicLoadSuccess = false
        var interceptionSuccess = false

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("CHILD_DYNAMIC_LOAD: VPORTABLE_GATE_4_DYNAMIC_SUCCESS") ||
                logLine.contains("GATE_4_RESULT: DYNAMIC_VFS_SUCCESS")) {
                dynamicLoadSuccess = true
            }
            if (logLine.contains("/lib/libvfs_shared.so") && logLine.contains("Redirected")) {
                interceptionSuccess = true
            }
        }

        val success = processResult.exitCode == 0 && dynamicLoadSuccess

        if (success) {
            emit(ProbeEvent.Log("  -> Dynamic library openat interception & symbol resolution verified (SUCCESS)"))
            if (interceptionSuccess) {
                emit(ProbeEvent.Log("  -> Supervisor openat redirection confirmed for /lib/libvfs_shared.so"))
            }
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 4 Feasibility Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: Dynamic linking VFS path redirection (/lib) validated."))
            emit(ProbeEvent.Completed(true, "PASSED (GATE 4)", true))
        } else {
            val badge = if (processResult.exitCode != 0) {
                when (processResult.exitCode) {
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_${processResult.exitCode}"
                }
            } else {
                "FAILED: VFS_GATE_4_FAILED"
            }
            emit(ProbeEvent.Log("FAILURE: $badge"))
            emit(ProbeEvent.Completed(false, badge, false))
        }
    }.flowOn(Dispatchers.IO)

    fun runGate5Probe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 5: Minimal Container Lifecycle & Dynamic-Driven Engine ==="))

        val rootfsDir = File(context.filesDir, "rootfs")
        val binDir = File(rootfsDir, "bin")
        val libDir = File(rootfsDir, "lib")
        val lib64Dir = File(rootfsDir, "lib64")
        val etcDir = File(rootfsDir, "etc")
        binDir.mkdirs()
        libDir.mkdirs()
        lib64Dir.mkdirs()
        etcDir.mkdirs()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePtraceProbePath = "$nativeDir/libprobe_ptrace.so"
        val nativeVfsSharedPath = "$nativeDir/libvfs_shared.so"
        val nativeVfsDynamicBinPath = "$nativeDir/libvfs_dynamic_bin.so"

        val targetSharedLib = File(libDir, "libvfs_shared.so")
        val sourceSharedLib = File(nativeVfsSharedPath)
        if (sourceSharedLib.exists()) {
            val t1 = File(libDir, "libvfs_shared.so")
            val t2 = File(lib64Dir, "libvfs_shared.so")

            sourceSharedLib.copyTo(t1, overwrite = true)
            sourceSharedLib.copyTo(t2, overwrite = true)

            t1.setReadable(true, false)
            t2.setReadable(true, false)

            emit(ProbeEvent.Log("Staged libvfs_shared.so verified: ${t1.absolutePath} (exists: ${t1.exists()}, size: ${t1.length()} bytes)"))
        }

        val targetDynamicApp = File(binDir, "dynamic_app")
        val sourceDynamicApp = File(nativeVfsDynamicBinPath)
        if (sourceDynamicApp.exists()) {
            sourceDynamicApp.copyTo(targetDynamicApp, overwrite = true)
            targetDynamicApp.setExecutable(true)
            emit(ProbeEvent.Log("Staged dynamic executable: ${targetDynamicApp.absolutePath}"))
        }

        val symlinkApp = File(binDir, "app_link")
        if (symlinkApp.exists()) {
            symlinkApp.delete()
        }
        try {
            android.system.Os.symlink("dynamic_app", symlinkApp.absolutePath)
            emit(ProbeEvent.Log("Created symlink: ${symlinkApp.absolutePath} -> dynamic_app"))
        } catch (e: Exception) {
            emit(ProbeEvent.Log("WARNING: Could not create symlink using Os.symlink: ${e.message}"))
        }

        emit(ProbeEvent.Log("[1/4] Staging virtual rootfs structure in ${rootfsDir.absolutePath}..."))

        val ptraceBinary = File(nativePtraceProbePath)
        if (!ptraceBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Native supervisor missing in $nativeDir ($nativePtraceProbePath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        if (!targetSharedLib.exists()) {
            emit(ProbeEvent.Log("ERROR: Staged shared library missing at ${targetSharedLib.absolutePath}"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        if (!targetDynamicApp.exists()) {
            emit(ProbeEvent.Log("ERROR: Staged dynamic application missing at ${targetDynamicApp.absolutePath}"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT", false))
            return@flow
        }

        emit(ProbeEvent.Log("[2/4] Native binaries verified at $nativeDir"))
        emit(ProbeEvent.Log("       Supervisor: ${ptraceBinary.name} (canExecute: ${ptraceBinary.canExecute()})"))

        val env = mapOf("PROOT_NO_SECCOMP" to "1")
        val command = listOf(nativePtraceProbePath, rootfsDir.absolutePath, nativeDir, "/bin/app_link")

        val testCase = TestCase(
            name = "Gate 5 Container Lifecycle Probe",
            command = command,
            env = env
        )

        emit(ProbeEvent.Log("\n[3/4] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

        var lifecycleSuccess = false

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("GATE_5_RESULT: CONTAINER_LIFECYCLE_SUCCESS")) {
                lifecycleSuccess = true
            }
        }

        val success = processResult.exitCode == 0 && lifecycleSuccess

        if (success) {
            emit(ProbeEvent.Log("  -> Container lifecycle & automatic dynamic linking verified (SUCCESS)"))
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[4/4] Evaluating Gate 5 Feasibility Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: Minimal container lifecycle & dynamic engine validated."))
            emit(ProbeEvent.Completed(true, "PASSED (GATE 5)", true))
        } else {
            val badge = if (processResult.exitCode != 0) {
                when (processResult.exitCode) {
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_${processResult.exitCode}"
                }
            } else {
                "FAILED: CONTAINER_LIFECYCLE_FAILED"
            }
            emit(ProbeEvent.Log("FAILURE: $badge"))
            emit(ProbeEvent.Completed(false, badge, false))
        }
    }.flowOn(Dispatchers.IO)

    fun runGate6AlpineProbe(): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Log("=== Gate 6: V-Flash Engine & Alpine Linux Mini-Rootfs Boot ==="))

        val rootfsDir = File(context.filesDir, "rootfs")
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePtraceProbePath = "$nativeDir/libprobe_ptrace.so"

        emit(ProbeEvent.Log("[1/5] Extracting Alpine mini-rootfs tarball via VFlashEngine..."))
        val extractSuccess = VFlashEngine.extractAlpineRootfs(context, VFlashEngine.DEFAULT_ALPINE_ASSET, rootfsDir)

        if (!extractSuccess) {
            emit(ProbeEvent.Log("ERROR: VFlashEngine rootfs extraction failed or missing essential binaries."))
            emit(ProbeEvent.Completed(false, "FAILED: VFLASH_EXTRACT_FAILED", false))
            return@flow
        }
        emit(ProbeEvent.Log("       Rootfs extracted successfully to: ${rootfsDir.absolutePath}"))

        emit(ProbeEvent.Log("[2/5] Verifying extracted Alpine files (/bin/sh and /etc/os-release)..."))
        val binSh = File(rootfsDir, "bin/sh")
        val osRelease = File(rootfsDir, "etc/os-release")

        if (!binSh.exists()) {
            emit(ProbeEvent.Log("ERROR: Target /bin/sh missing in extracted rootfs"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT (/bin/sh)", false))
            return@flow
        }

        if (!osRelease.exists()) {
            emit(ProbeEvent.Log("ERROR: Target /etc/os-release missing in extracted rootfs"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT (/etc/os-release)", false))
            return@flow
        }

        emit(ProbeEvent.Log("       /bin/sh verified: ${binSh.absolutePath}"))
        emit(ProbeEvent.Log("       /etc/os-release verified: ${osRelease.absolutePath}"))

        val ptraceBinary = File(nativePtraceProbePath)
        if (!ptraceBinary.exists()) {
            emit(ProbeEvent.Log("ERROR: Native supervisor missing in $nativeDir ($nativePtraceProbePath)"))
            emit(ProbeEvent.Completed(false, "FAILED: ENOENT (supervisor)", false))
            return@flow
        }

        emit(ProbeEvent.Log("[3/5] Native supervisor verified at $nativeDir (${ptraceBinary.name})"))

        val targetCmd = "echo VPORTABLE_ALPINE_BOOT_OK && uname -a && cat /etc/os-release"
        val env = mapOf("PROOT_NO_SECCOMP" to "1")
        val command = listOf(nativePtraceProbePath, rootfsDir.absolutePath, nativeDir, "/bin/sh", "-c", targetCmd)

        val testCase = TestCase(
            name = "Gate 6 V-Flash & Alpine Mini-Rootfs Boot Probe",
            command = command,
            env = env
        )

        emit(ProbeEvent.Log("\n[4/5] Executing ${testCase.name}: ${testCase.command.joinToString(" ")}"))

        var bootMarkerFound = false
        var osReleaseFound = false

        val processResult = executeSubprocessSuspend(testCase.command, testCase.env) { logLine ->
            emit(ProbeEvent.Log("  $logLine"))
            if (logLine.contains("VPORTABLE_ALPINE_BOOT_OK")) {
                bootMarkerFound = true
            }
            if (logLine.contains("NAME=\"Alpine Linux\"") || logLine.contains("Alpine Linux")) {
                osReleaseFound = true
            }
        }

        val success = processResult.exitCode == 0 && bootMarkerFound && osReleaseFound

        if (success) {
            emit(ProbeEvent.Log("  -> V-Flash Engine & Alpine Linux boot verified (SUCCESS)"))
        } else {
            val reason = processResult.failureBadge
            emit(ProbeEvent.Log("  -> Subprocess failed with exit code ${processResult.exitCode} ($reason)"))
        }

        emit(ProbeEvent.Log("\n[5/5] Evaluating Gate 6 Feasibility Results..."))
        if (success) {
            emit(ProbeEvent.Log("SUCCESS: V-Flash Engine & Alpine Linux mini-rootfs boot validated."))
            emit(ProbeEvent.Completed(true, "PASSED (V-FLASH & ALPINE BOOT)", true))
        } else {
            val badge = if (processResult.exitCode != 0) {
                when (processResult.exitCode) {
                    126 -> "FAILED: EPERM"
                    127 -> "FAILED: ENOENT"
                    139 -> "FAILED: SIGSEGV"
                    159 -> "FAILED: SIGSYS"
                    else -> "FAILED: EXIT_${processResult.exitCode}"
                }
            } else {
                "FAILED: ALPINE_BOOT_FAILED"
            }
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
        @JvmStatic
        internal fun executeSubprocess(
            command: List<String>,
            env: Map<String, String> = emptyMap(),
            onLine: (String) -> Unit
        ): ProcessOutput {
            return kotlinx.coroutines.runBlocking {
                executeSubprocessSuspend(command, env) { line -> onLine(line) }
            }
        }

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
    }
}
