package com.m5dev.vportable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m5dev.vportable.container.ContainerDashboard
import com.m5dev.vportable.container.ContainerInfo
import com.m5dev.vportable.container.ContainerStatus
import com.m5dev.vportable.container.ContainerViewModel
import com.m5dev.vportable.terminal.TerminalScreen
import com.m5dev.vportable.terminal.TerminalViewModel

private const val SCREEN_DASHBOARD = 0
private const val SCREEN_TERMINAL = 1
private const val SCREEN_TEST_HARNESS = 2

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val terminalViewModel: TerminalViewModel by viewModels()
    private val containerViewModel: ContainerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0D0D0D)
                ) {
                    var currentScreen by remember { mutableIntStateOf(SCREEN_DASHBOARD) }
                    var selectedContainer by remember { mutableStateOf<ContainerInfo?>(null) }

                    // The PTY is a single global session, so mirror its state onto
                    // the container the user opened; that is what makes RUNNING and
                    // EXTRACTING appear at all.
                    val terminalState by terminalViewModel.uiState.collectAsState()
                    val activeContainerId = selectedContainer?.id

                    // Set once the PTY has actually reported active for this
                    // container. Without it the first emission (both flags false,
                    // while startTerminal() is still spinning up) would overwrite
                    // the optimistic RUNNING the card just set.
                    var terminalWasActive by remember(activeContainerId) {
                        mutableStateOf(false)
                    }

                    LaunchedEffect(
                        terminalState.isTerminalActive,
                        terminalState.isExtractingRootfs,
                        activeContainerId
                    ) {
                        val id = activeContainerId ?: return@LaunchedEffect
                        when {
                            terminalState.isTerminalActive -> {
                                terminalWasActive = true
                                containerViewModel.updateContainerStatus(
                                    id, ContainerStatus.RUNNING
                                )
                            }

                            terminalState.isExtractingRootfs -> {
                                containerViewModel.updateContainerStatus(
                                    id, ContainerStatus.EXTRACTING
                                )
                            }

                            // Only report STOPPED for a session that really ended,
                            // so a container that was merely opened keeps the
                            // status its card set.
                            terminalWasActive -> {
                                terminalWasActive = false
                                containerViewModel.updateContainerStatus(
                                    id, ContainerStatus.STOPPED
                                )
                            }
                        }
                    }

                    // Back returns to the dashboard from any other screen, and is
                    // consumed so it cannot also leave the activity.
                    BackHandler(enabled = currentScreen != SCREEN_DASHBOARD) {
                        currentScreen = SCREEN_DASHBOARD
                        containerViewModel.refresh()
                    }

                    when (currentScreen) {
                        SCREEN_TERMINAL -> TerminalScreen(viewModel = terminalViewModel)

                        SCREEN_TEST_HARNESS -> {
                            val state by mainViewModel.uiState.collectAsState()
                            Column(modifier = Modifier.fillMaxSize()) {
                                Gate1TestHarnessScreen(
                                    state = state,
                                    onRunTest = { mainViewModel.runSyscallTest() }
                                )
                            }
                        }

                        else -> {
                            val context = LocalContext.current
                            ContainerDashboard(
                                viewModel = containerViewModel,
                                onOpenTerminal = { container ->
                                    // Null comes from the header button, which
                                    // opens a terminal without a specific
                                    // container; both paths boot the same rootfs
                                    // until multi-container is wired up.
                                    selectedContainer = container
                                    currentScreen = SCREEN_TERMINAL
                                    // bootAlpine() does not exist in this tree —
                                    // startTerminal() is the real entry point and
                                    // guards against a double start.
                                    terminalViewModel.startTerminal(context)
                                },
                                modifier = Modifier.fillMaxSize(),
                                onHeaderTripleTap = { currentScreen = SCREEN_TEST_HARNESS }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Gate1TestHarnessScreen(
    state: UiState,
    onRunTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "VPortable V-Flash & Alpine Boot Test Harness",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        )

        // Host Status Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Host System Info", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50), fontSize = 14.sp)
                Text("Architecture (ABIs): ${state.supportedAbis}", color = Color.LightGray, fontSize = 12.sp)
                Text("Android Version: ${state.androidVersion}", color = Color.LightGray, fontSize = 12.sp)
                Text("Kernel Release: ${state.kernelRelease}", color = Color.LightGray, fontSize = 12.sp)
            }
        }

        // Action Button & Result Badge Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRunTest,
                enabled = !state.isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3),
                    disabledContainerColor = Color(0xFF424242)
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).height(16.dp).padding(2.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
                Text("Run V-Flash & Alpine Boot Test", color = Color.White)
            }

            state.badgeText?.let { badge ->
                val badgeBg = if (state.badgeColorGreen) Color(0xFF2E7D32) else Color(0xFFC62828)
                Box(
                    modifier = Modifier
                        .background(badgeBg, shape = RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = badge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Console Log Terminal Output",
            style = MaterialTheme.typography.titleMedium.copy(color = Color.LightGray)
        )

        // Console / Log Terminal View
        val listState = rememberLazyListState()
        LaunchedEffect(state.logs.size) {
            if (state.logs.isNotEmpty()) {
                listState.animateScrollToItem(state.logs.size - 1)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A), shape = RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF333333), shape = RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            if (state.logs.isEmpty()) {
                Text(
                    text = "Tap 'Run V-Flash & Alpine Boot Test' to start VFS interception & Alpine boot probe...",
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            } else {
                LazyColumn(state = listState) {
                    items(state.logs) { log ->
                        Text(
                            text = log,
                            color = when {
                                log.startsWith("ERROR") || log.contains("FAILED") -> Color(0xFFFF5252)
                                log.startsWith("SUCCESS") || log.contains("SUCCESS") -> Color(0xFF69F0AE)
                                log.startsWith("[") -> Color(0xFF4FC3F7)
                                else -> Color(0xFFE0E0E0)
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}
