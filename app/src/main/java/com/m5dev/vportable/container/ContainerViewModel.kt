package com.m5dev.vportable.container

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "ContainerScan"

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

    /** Cancelled and replaced on each scan so the newest result always wins. */
    private var scanJob: Job? = null

    init {
        scanContainers()
    }

    /** Re-scans on demand, e.g. when the dashboard is shown again. */
    fun refresh() {
        scanContainers()
    }

    fun scanContainers() {
        // init() and the dashboard's LaunchedEffect(Unit) both scan on first
        // show; cancelling the older job keeps that to a single pass and stops
        // a stale result from landing after a newer one.
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)

            // Sizing a rootfs means walking every file in it; keep that off the
            // main thread so the dashboard stays responsive while it runs. The
            // catch matters: without it any failure would leave isScanning set
            // and the dashboard spinning forever.
            val found = try {
                withContext(Dispatchers.IO) {
                    discoverRootfsDirs().map { (id, name, dir) ->
                        ContainerInfo(
                            id = id,
                            name = name,
                            distro = "Alpine Linux",
                            version = readAlpineVersion(dir),
                            arch = mapAbiToArch(Build.SUPPORTED_ABIS.firstOrNull()),
                            status = ContainerStatus.STOPPED,
                            storageMb = dirSizeMb(dir),
                            rootfsPath = dir.absolutePath
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Container scan failed: ${e.message}")
                emptyList()
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

    /**
     * Total size of [dir] in MB, counted without following links out of the tree.
     *
     * [File.isFile] and [File.listFiles] both follow symlinks, and a rootfs has
     * plenty of them: Alpine points every applet in /usr/bin at /bin/busybox, and
     * ships absolute directory links such as /var/run -> /run and
     * /etc/ssl1.1/certs -> /etc/ssl/certs. Those absolute targets resolve outside
     * the rootfs, so a naive walk descends into the system filesystem; a link
     * pointing at its own ancestor loops forever. Comparing canonical paths keeps
     * the walk inside the rootfs and counts each real file once.
     */
    private fun dirSizeMb(dir: File): Int = try {
        val rootPath = dir.canonicalPath
        var total = 0L
        val seen = HashSet<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            val path = try {
                f.canonicalPath
            } catch (_: IOException) {
                null
            }
            if (path == null) continue
            if (path != rootPath && !path.startsWith(rootPath + File.separator)) continue
            if (!seen.add(path)) continue
            if (f.isFile) {
                total += f.length()
            } else {
                f.listFiles()?.forEach { stack.addLast(it) }
            }
        }
        (total / (1024 * 1024)).toInt().coerceAtLeast(1)
    } catch (_: Exception) {
        0
    }

    private fun readAlpineVersion(rootfs: File): String {
        return try {
            val release = File(rootfs, "etc/alpine-release")
            if (release.exists()) return "v${release.readText().trim()}"

            File(rootfs, "etc/os-release")
                .readLines()
                .firstOrNull { it.startsWith("VERSION_ID=") }
                ?.removePrefix("VERSION_ID=")
                ?.trim('"')
                ?.let { "v$it" }
                ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun updateContainerStatus(id: String, status: ContainerStatus) {
        _uiState.value = _uiState.value.copy(
            containers = _uiState.value.containers.map {
                if (it.id == id) it.copy(status = status) else it
            }
        )
    }
}
