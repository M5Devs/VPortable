package com.m5dev.vportable.terminal

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m5dev.vportable.flasher.VFlashEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TerminalUiState(
    val isTerminalActive: Boolean = false,
    val isExtractingRootfs: Boolean = false,
    val terminalOutput: String = "",
    val statusMessage: String = "PTY Inactive"
)

class TerminalViewModel @JvmOverloads constructor(
    application: Application,
    private val bridge: TerminalBridge = TerminalBridge()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var masterFd: Int = -1
    private var readJob: Job? = null

    fun startTerminal(context: Context, cols: Int = 80, rows: Int = 24) {
        if (_uiState.value.isTerminalActive || _uiState.value.isExtractingRootfs) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isExtractingRootfs = true,
                statusMessage = "Preparing Alpine rootfs..."
            )

            val rootfsDir = File(context.filesDir, "rootfs")
            val nativeDir = context.applicationInfo.nativeLibraryDir

            val extracted = withContext(Dispatchers.IO) {
                VFlashEngine.extractAlpineRootfs(
                    context = context,
                    assetPath = VFlashEngine.DEFAULT_ALPINE_ASSET,
                    targetDir = rootfsDir
                )
            }

            if (!extracted) {
                _uiState.value = _uiState.value.copy(
                    isExtractingRootfs = false,
                    statusMessage = "Failed to extract Alpine rootfs"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isExtractingRootfs = false,
                statusMessage = "Starting PTY Session..."
            )

            val fd = withContext(Dispatchers.IO) {
                bridge.nativeStartPty(
                    rootfsPath = rootfsDir.absolutePath,
                    nativeDir = nativeDir,
                    cols = cols,
                    rows = rows
                )
            }

            if (fd < 0) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Failed to initialize native PTY"
                )
                return@launch
            }

            masterFd = fd
            _uiState.value = _uiState.value.copy(
                isTerminalActive = true,
                statusMessage = "Alpine Linux Shell (PTY Active)"
            )

            readJob = launch {
                bridge.readStream(masterFd).collect { text ->
                    _uiState.value = _uiState.value.copy(
                        terminalOutput = _uiState.value.terminalOutput + processAnsi(text)
                    )
                }
            }
        }
    }

    private fun processAnsi(rawText: String): String {
        // Strip ANSI escape codes for clean monospaced rendering
        return rawText.replace(Regex("\u001B\\[[;\\d]*[A-Za-z]"), "")
    }

    fun sendRawInput(text: String) {
        if (masterFd < 0 || !_uiState.value.isTerminalActive) return
        val payload = text.toByteArray()
        viewModelScope.launch(Dispatchers.IO) {
            bridge.nativeWrite(masterFd, payload, payload.size)
        }
    }

    fun sendEnter() {
        sendBytes(byteArrayOf(0x0D)) // '\r'
    }

    fun sendBackspace() {
        sendBytes(byteArrayOf(0x7F)) // DEL / Backspace
    }

    fun sendCtrlC() {
        sendBytes(byteArrayOf(0x03))
    }

    fun sendTab() {
        sendBytes(byteArrayOf(0x09))
    }

    fun sendEsc() {
        sendBytes(byteArrayOf(0x1B))
    }

    fun sendBytes(bytes: ByteArray) {
        if (masterFd < 0 || !_uiState.value.isTerminalActive) return
        viewModelScope.launch(Dispatchers.IO) {
            bridge.nativeWrite(masterFd, bytes, bytes.size)
        }
    }

    fun clearScreen() {
        _uiState.value = _uiState.value.copy(terminalOutput = "")
    }

    fun closeTerminal() {
        readJob?.cancel()
        readJob = null
        if (masterFd >= 0) {
            val fdToClose = masterFd
            masterFd = -1
            viewModelScope.launch(Dispatchers.IO) {
                bridge.nativeClose(fdToClose)
            }
        }
        _uiState.value = _uiState.value.copy(
            isTerminalActive = false,
            statusMessage = "PTY Session Terminated"
        )
    }

    override fun onCleared() {
        super.onCleared()
        closeTerminal()
    }
}
