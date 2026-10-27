package com.m5dev.vportable

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m5dev.vportable.runner.ProbeEvent
import com.m5dev.vportable.runner.SyscallProbeRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val supportedAbis: String = Build.SUPPORTED_ABIS.joinToString(", "),
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val kernelRelease: String = System.getProperty("os.version") ?: "Unknown",
    val logs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val badgeText: String? = null,
    val badgeColorGreen: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val runner = SyscallProbeRunner(application)

    fun runSyscallTest() {
        if (_uiState.value.isRunning) return

        _uiState.value = _uiState.value.copy(
            logs = emptyList(),
            isRunning = true,
            badgeText = null
        )

        viewModelScope.launch {
            runner.runGate6AlpineProbe().collect { event ->
                when (event) {
                    is ProbeEvent.Log -> {
                        _uiState.value = _uiState.value.copy(
                            logs = _uiState.value.logs + event.line
                        )
                    }
                    is ProbeEvent.Completed -> {
                        _uiState.value = _uiState.value.copy(
                            isRunning = false,
                            badgeText = event.badgeText,
                            badgeColorGreen = event.badgeColorGreen
                        )
                    }
                }
            }
        }
    }
}
