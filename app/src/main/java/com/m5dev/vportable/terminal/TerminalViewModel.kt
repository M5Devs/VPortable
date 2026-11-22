package com.m5dev.vportable.terminal

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m5dev.vportable.flasher.VFlashEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "TerminalInput"

// Lines kept in the scrollback buffer, and how many of them are published to
// the UI. Rendering is bounded by the latter, so a huge install no longer makes
// recomposition cost grow with total output.
private const val MAX_SCROLLBACK_LINES = 800
private const val MAX_VISIBLE_LINES = 300

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

    // Size the UI last measured from the real canvas, and the size last pushed to
    // the PTY. The former lets a PTY start at the true width instead of 80x24; the
    // latter keeps rotation and IME changes from re-issuing an identical ioctl.
    private var measuredCols = 0
    private var measuredRows = 0
    private var appliedCols = -1
    private var appliedRows = -1

    private val terminalBuffer = TerminalBuffer(maxLines = MAX_SCROLLBACK_LINES)

    // Single-threaded dispatcher: serialises PTY writes so keystrokes are not
    // reordered by the thread pool.
    private val writeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vportable-pty-writer")
    }.asCoroutineDispatcher()

    fun startTerminal(context: Context, cols: Int = 80, rows: Int = 24) {
        if (_uiState.value.isTerminalActive || _uiState.value.isExtractingRootfs) return

        // Prefer the size the UI has measured, so the shell starts at the real
        // width instead of 80 columns and the first prompt is not wrapped wrongly.
        val startCols = if (measuredCols > 0) measuredCols else cols
        val startRows = if (measuredRows > 0) measuredRows else rows

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
                    cols = startCols,
                    rows = startRows
                )
            }

            if (fd < 0) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Failed to initialize native PTY"
                )
                return@launch
            }

            masterFd = fd
            // The PTY was created at this size, so record it as applied; otherwise
            // the first resizeTerminal() from the UI would repeat an identical ioctl.
            appliedCols = startCols
            appliedRows = startRows
            _uiState.value = _uiState.value.copy(
                isTerminalActive = true,
                statusMessage = "Alpine Linux Shell (PTY Active)"
            )

            readJob = launch {
                try {
                    bridge.readStream(masterFd).collect { raw ->
                        val clean = AnsiProcessor.strip(raw)
                        if (clean.isEmpty()) return@collect
                        Log.d(TAG, "nativeRead -> ${clean.length} chars")

                        // The tty echoes a delete as BS followed by an erase
                        // sequence, and that echo routinely arrives in a later
                        // read() than the character it erases. Applying it to the
                        // buffer's last line keeps that cross-chunk case working;
                        // stripping it per chunk would not.
                        var start = 0
                        while (start < clean.length && clean[start] == '\b') {
                            terminalBuffer.applyBackspace()
                            start++
                        }
                        terminalBuffer.appendRaw(applyBackspace(clean.substring(start)))

                        // Publish only the tail. Joining a bounded number of lines
                        // keeps this O(visible) instead of O(total output).
                        val visible = terminalBuffer.getVisibleLines(MAX_VISIBLE_LINES)
                            .joinToString("\n")
                        _uiState.value = _uiState.value.copy(terminalOutput = visible)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "PTY read loop ended: ${t.message}")
                }
                // readStream() only returns when nativeRead() reports EOF or a hard
                // error (master fd closed / child gone). Surface that instead of
                // leaving the screen looking alive but frozen.
                if (masterFd >= 0 && _uiState.value.isTerminalActive) {
                    Log.w(TAG, "PTY read loop finished unexpectedly; marking session closed")
                    _uiState.value = _uiState.value.copy(
                        isTerminalActive = false,
                        statusMessage = "PTY Session Closed (shell exited)"
                    )
                }
            }
        }
    }

    /**
     * Applies the erase sequence the tty echoes when a key is deleted (the
     * terminal sends BS then CSI-erase-down). Without this the backspace was
     * appended verbatim and the erased character stayed visible. Handles the
     * case where the echo of a delete arrives in the same read as the character
     * it erases; a leading backspace, whose character arrived in an earlier
     * read, is applied to the buffer instead.
     * Idempotent, since the result contains no backspace.
     */
    private fun applyBackspace(text: String): String {
        if (text.indexOf('\b') < 0) return text

        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '\b' -> if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                    sb.setLength(sb.length - 1)
                }
                '\u0007', '\u0000' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Resizes the PTY to the terminal grid the UI can actually display.
     *
     * The size is remembered even when no PTY is running yet: the screen is
     * composed before the rootfs has finished extracting, so the first call
     * arrives with `masterFd == -1`. Keeping it lets `startTerminal()` create the
     * PTY at the right width instead of losing that measurement.
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        measuredCols = cols
        measuredRows = rows

        val fd = masterFd
        if (fd < 0) return
        if (cols == appliedCols && rows == appliedRows) return

        appliedCols = cols
        appliedRows = rows
        viewModelScope.launch(Dispatchers.IO) {
            bridge.nativeResize(fd, cols, rows)
        }
    }

    fun sendRawInput(text: String) {
        if (masterFd < 0 || !_uiState.value.isTerminalActive) return
        Log.d(TAG, "sendRawInput ${formatBytes(text.toByteArray())}")
        sendBytes(text.toByteArray())
    }

    fun sendEnter() {
        sendBytes(byteArrayOf(0x0D)) // 0x0D ('\r') translated to '\n' by ICRNL
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
        if (masterFd < 0 || !_uiState.value.isTerminalActive) {
            Log.d(TAG, "sendBytes dropped (inactive): ${formatBytes(bytes)}")
            return
        }
        val fd = masterFd
        Log.d(TAG, "sendBytes ${formatBytes(bytes)}")
        // All writes go through one single-threaded dispatcher so keystrokes
        // reach the PTY in the exact order they were produced.
        viewModelScope.launch(writeDispatcher) {
            val written = bridge.nativeWrite(fd, bytes, bytes.size)
            Log.d(TAG, "nativeWrite($fd) -> $written / ${bytes.size}")
        }
    }

    private fun formatBytes(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }

    fun clearScreen() {
        terminalBuffer.clear()
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
        // The next PTY is a fresh descriptor, so nothing has been applied to it yet.
        appliedCols = -1
        appliedRows = -1
        _uiState.value = _uiState.value.copy(
            isTerminalActive = false,
            statusMessage = "PTY Session Terminated"
        )
    }

    override fun onCleared() {
        super.onCleared()
        closeTerminal()
        writeDispatcher.close()
    }
}
