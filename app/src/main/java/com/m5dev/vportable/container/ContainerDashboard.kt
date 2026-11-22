package com.m5dev.vportable.container

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DashBackground = Color(0xFF0D0D0D)
private val DashCard = Color(0xFF1A1A1A)
private val DashGreen = Color(0xFF50FA7B)
private val DashOrange = Color(0xFFFFB86C)
private val DashRed = Color(0xFFFF5555)
private val DashText = Color(0xFFF8F8F2)
private val DashSecondary = Color(0xFF6272A4)
private val DashChip = Color(0xFF1E1E2E)
private val DashBorder = Color(0xFF333333)

/**
 * Container list, styled after Winlator's container manager.
 *
 * [onOpenTerminal] is invoked with the container the user wants a shell in; the
 * host decides what that means.
 */
@Composable
fun ContainerDashboard(
    viewModel: ContainerViewModel,
    onOpenTerminal: (ContainerInfo) -> Unit,
    modifier: Modifier = Modifier,
    onHeaderTripleTap: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    // The rootfs can be created or changed while the dashboard is not visible
    // (e.g. the first terminal boot extracts it), so re-scan on each entry.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Box(modifier = modifier.fillMaxSize().background(DashBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DashboardHeader(
                arch = state.deviceArch,
                androidVersion = state.androidVersion,
                onTripleTap = onHeaderTripleTap
            )

            when {
                state.isScanning && state.containers.isEmpty() -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = DashGreen,
                            strokeWidth = 3.dp
                        )
                    }
                }

                state.containers.isEmpty() -> EmptyState(
                    isScanning = false,
                    onCreate = { message = "Coming soon: new container wizard" },
                    modifier = Modifier.weight(1f)
                )

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.containers, key = { it.id }) { container ->
                        ContainerCard(
                            container = container,
                            onOpenTerminal = {
                                viewModel.updateContainerStatus(
                                    container.id,
                                    ContainerStatus.RUNNING
                                )
                                onOpenTerminal(container)
                            },
                            onStop = {
                                viewModel.updateContainerStatus(
                                    container.id,
                                    ContainerStatus.STOPPED
                                )
                            },
                            onDelete = {
                                message = "Delete is not available yet"
                            }
                        )
                    }
                }
            }
        }

        if (state.containers.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(DashGreen)
                    .clickable { message = "Coming soon: new container wizard" },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        message?.let { text ->
            MessageBar(
                text = text,
                onDismiss = { message = null },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** Two taps within this window count towards the same gesture. */
private const val TAP_WINDOW_MS = 600L

@Composable
private fun DashboardHeader(
    arch: String,
    androidVersion: String,
    onTripleTap: () -> Unit
) {
    // The developer entry point is a triple-tap on the title, so it stays out of
    // the normal flow.
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt > TAP_WINDOW_MS) 1 else tapCount + 1
                lastTapAt = now
                if (tapCount >= 3) {
                    tapCount = 0
                    lastTapAt = 0L
                    onTripleTap()
                }
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                text = "VPortable",
                color = DashText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Container Runtime",
                color = DashSecondary,
                fontSize = 11.sp
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(DashChip)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "$arch · $androidVersion",
                color = DashSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ContainerCard(
    container: ContainerInfo,
    onOpenTerminal: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = when (container.status) {
        ContainerStatus.RUNNING -> DashGreen.copy(alpha = 0.6f)
        ContainerStatus.ERROR -> DashRed.copy(alpha = 0.6f)
        ContainerStatus.EXTRACTING -> DashOrange.copy(alpha = 0.6f)
        ContainerStatus.STOPPED -> DashBorder
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DashCard)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🏔️", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = container.name,
                    color = DashText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "${container.version} · ${container.arch}",
                color = DashSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "💾 ${container.storageMb} MB",
                color = DashSecondary,
                fontSize = 11.sp
            )
            StatusPill(container.status)
        }

        Column(
            modifier = Modifier.padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ActionButton(
                label = "▶  Shell",
                background = Color(0xFF1A472A),
                contentColor = DashGreen,
                bold = true,
                onClick = onOpenTerminal
            )

            when (container.status) {
                ContainerStatus.RUNNING -> ActionButton(
                    label = "■  Stop",
                    background = Color(0xFF3D1515),
                    contentColor = DashRed,
                    onClick = onStop
                )

                else -> {
                    ActionButton(
                        label = "⚙  Settings",
                        background = Color(0xFF2D2D2D),
                        contentColor = Color(0xFFAAAAAA),
                        onClick = { }
                    )
                    ActionButton(
                        label = "🗑  Delete",
                        background = Color(0xFF3D1515),
                        contentColor = DashRed,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: ContainerStatus) {
    val background: Color
    val contentColor: Color
    var label = ""

    when (status) {
        ContainerStatus.RUNNING -> {
            background = Color(0xFF1A472A); contentColor = DashGreen; label = "● RUNNING"
        }
        ContainerStatus.STOPPED -> {
            background = Color(0xFF2D2D2D); contentColor = Color(0xFF888888); label = "○ STOPPED"
        }
        ContainerStatus.EXTRACTING -> {
            background = Color(0xFF2D1A00); contentColor = DashOrange
        }
        ContainerStatus.ERROR -> {
            background = Color(0xFF3D1515); contentColor = DashRed; label = "✕ ERROR"
        }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == ContainerStatus.EXTRACTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = contentColor,
                strokeWidth = 1.5.dp
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "EXTRACTING",
                color = contentColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = label,
                color = contentColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    background: Color,
    contentColor: Color,
    bold: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = contentColor,
        fontSize = 11.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun EmptyState(isScanning: Boolean, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "📦", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isScanning) "Scanning…" else "No containers yet",
            color = DashText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tap + to create your first container",
            color = DashSecondary,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "+ New Container",
            color = Color.Black,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(DashGreen)
                .clickable(onClick = onCreate)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = DashText,
        fontSize = 12.sp,
        modifier = modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2D2D2D))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
