package com.m5dev.vportable.container

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ContainerStatus { STOPPED, RUNNING, EXTRACTING, ERROR }

data class ContainerInfo(
    val id: String,
    val name: String,
    val distro: String,
    val version: String,
    val arch: String,
    val status: ContainerStatus = ContainerStatus.STOPPED,
    val storageMb: Int = 0,
    val rootfsPath: String = ""
)

data class DashboardUiState(
    val containers: List<ContainerInfo> = emptyList(),
    val isScanning: Boolean = false,
    val deviceArch: String = mapAbiToArch(Build.SUPPORTED_ABIS.firstOrNull()),
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
)

/** Maps an Android ABI name onto the name the distro reports for itself. */
internal fun mapAbiToArch(abi: String?): String = when (abi) {
    null -> "unknown"
    "arm64-v8a" -> "aarch64"
    "armeabi-v7a", "armeabi" -> "armv7"
    else -> abi
}

/**
 * Lists the container root filesystems present on disk.
 *
 * Two layouts are recognised: the flat [filesDir]/rootfs used by the current
 * boot path, and [filesDir]/&lt;name&gt;/rootfs for side-by-side containers. A
 * directory only counts as a rootfs when it has a `bin` directory, so unrelated
 * folders are not reported as containers.
 */
class ContainerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val filesDir = application.filesDir

    init {
        scanContainers()
    }

    fun scanContainers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)

            // Sizing a rootfs means walking every file in it; keep that off the
            // main thread so the dashboard stays responsive while it runs.
            val found = withContext(Dispatchers.IO) {
                discoverRootfsDirs().map { (id, name, dir) ->
                    ContainerInfo(
                        id = id,
                        name = name,
                        distro = "Alpine Linux",
                        version = readAlpineVersion(dir),
                        arch = mapAbiToArch(Build.SUPPORTED_ABIS.firstOrNull()),
                        status = ContainerStatus.STOPPED,
                        storageMb = directorySizeMb(dir),
                        rootfsPath = dir.absolutePath
                    )
                }
            }

            // Preserve a status the dashboard already knows about, so a rescan
            // does not flip a running container back to stopped.
            val previous = _uiState.value.containers.associateBy { it.id }
            _uiState.value = _uiState.value.copy(
                containers = found.map { c ->
                    previous[c.id]?.let { c.copy(status = it.status) } ?: c
                },
                isScanning = false
            )
        }
    }

    private fun discoverRootfsDirs(): List<Triple<String, String, File>> {
        val results = mutableListOf<Triple<String, String, File>>()

        val legacy = File(filesDir, "rootfs")
        if (isRootfs(legacy)) {
            results += Triple("alpine_default", "Alpine Linux", legacy)
        }

        filesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val nested = File(dir, "rootfs")
                if (dir.name != "rootfs" && isRootfs(nested)) {
                    results += Triple(dir.name, dir.name.replaceFirstChar { it.uppercase() }, nested)
                }
            }

        return results
    }

    private fun isRootfs(dir: File): Boolean = File(dir, "bin").isDirectory

    private fun directorySizeMb(rootfs: File): Int {
        val bytes = rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return (bytes / (1024 * 1024)).toInt().coerceAtLeast(1)
    }

    private fun readAlpineVersion(rootfs: File): String = try {
        File(rootfs, "etc/alpine-release").readText().trim().let { "v$it" }
    } catch (_: Exception) {
        "unknown"
    }

    fun updateContainerStatus(id: String, status: ContainerStatus) {
        _uiState.value = _uiState.value.copy(
            containers = _uiState.value.containers.map {
                if (it.id == id) it.copy(status = status) else it
            }
        )
    }
}
