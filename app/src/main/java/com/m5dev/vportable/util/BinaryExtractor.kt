package com.m5dev.vportable.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility responsible for extracting architecture-matched proot and busybox binaries
 * from app assets or verifying native library paths for execution.
 */
class BinaryExtractor(private val context: Context) {

    companion object {
        private const val TAG = "BinaryExtractor"
        const val BIN_DIR_NAME = "bin"
        val REQUIRED_BINARIES = listOf("proot", "busybox")

        fun selectAbiFolder(supportedAbis: Array<String>, availableAssetAbis: List<String>): String {
            for (abi in supportedAbis) {
                if (availableAssetAbis.contains(abi)) {
                    return abi
                }
                if (abi.startsWith("arm64") && availableAssetAbis.contains("arm64-v8a")) {
                    return "arm64-v8a"
                }
                if (abi.startsWith("x86_64") && availableAssetAbis.contains("x86_64")) {
                    return "x86_64"
                }
            }
            return if (availableAssetAbis.contains("x86_64")) "x86_64" else availableAssetAbis.firstOrNull() ?: "x86_64"
        }
    }

    fun getBinDirectory(): File {
        return File(context.filesDir, BIN_DIR_NAME)
    }

    /**
     * Attempts to find pre-extracted native libraries in applicationInfo.nativeLibraryDir
     * (which avoids Android 10+ W^X SELinux execution violations), falling back to extracting assets.
     */
    fun resolveOrExtractBinaries(): Result<File> {
        return runCatching {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val nativeProot = File(nativeLibDir, "libproot.so")
            val nativeBusybox = File(nativeLibDir, "libbusybox.so")

            if (nativeProot.exists() && nativeBusybox.exists()) {
                Log.i(TAG, "Found executable native libraries in ${nativeLibDir.absolutePath}")
                return@runCatching nativeLibDir
            }

            extractBinaries().getOrThrow()
        }
    }

    fun extractBinaries(): Result<File> {
        return runCatching {
            val targetBinDir = getBinDirectory()
            if (!targetBinDir.exists()) {
                targetBinDir.mkdirs()
            }

            val assetBinList = context.assets.list("bin")?.toList() ?: emptyList()
            val primaryAbi = selectAbiFolder(Build.SUPPORTED_ABIS, assetBinList)
            Log.i(TAG, "Selected ABI asset directory: $primaryAbi")

            val abiAssetPath = "bin/$primaryAbi"
            val binariesInAbi = context.assets.list(abiAssetPath) ?: emptyArray()

            for (binaryName in REQUIRED_BINARIES) {
                if (!binariesInAbi.contains(binaryName)) {
                    throw IllegalStateException("Binary $binaryName missing in assets at $abiAssetPath")
                }

                val targetFile = File(targetBinDir, binaryName)
                val assetFilePath = "$abiAssetPath/$binaryName"

                context.assets.open(assetFilePath).use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                val chmodSuccess = targetFile.setExecutable(true, false)
                if (!chmodSuccess) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("chmod", "755", targetFile.absolutePath)).waitFor()
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback chmod failed for ${targetFile.name}", e)
                    }
                }
                Log.i(TAG, "Extracted $binaryName to ${targetFile.absolutePath} (executable: ${targetFile.canExecute()})")
            }

            targetBinDir
        }
    }
}
