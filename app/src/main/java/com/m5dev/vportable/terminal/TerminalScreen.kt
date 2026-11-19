package com.m5dev.vportable.terminal

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val TermuxBackground = Color(0xFF000000)
private val TermuxForeground = Color(0xFFF8F8F2)
private val TermuxGreen = Color(0xFF50FA7B)
private val TermuxRed = Color(0xFFFF5555)
private val TermuxAmber = Color(0xFFFFB86C)
private val TermuxStatusBar = Color(0xFF1A1A1A)
private val TermuxToolbar = Color(0xFF2D2D2D)
private val TermuxKey = Color(0xFF3D3D3D)
private val TermuxKeyText = Color(0xFFE0E0E0)
private val TermuxEnterBackground = Color(0xFF1A472A)
private val TermuxKillBackground = Color(0xFF3D1515)

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val inputView = remember { TerminalInputView(context) }
    val inputMethodManager = remember {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    }
    val inputFocusRequester = remember { FocusRequester() }

    // The raw input view needs no text buffer; every commit goes straight to the PTY.
    DisposableEffect(inputView) {
        inputView.listener = TerminalInputView.Listener { bytes -> viewModel.sendBytes(bytes) }
        onDispose { inputView.listener = null }
    }

    // Focus the view and raise the IME. Focus goes through a Compose
    // FocusRequester on the AndroidView, which is what forwards focus into the
    // hosted View; showSoftInput is requested on the view after it is focused,
    // hence the post().
    fun focusInput() {
        try {
            inputFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // Not attached yet; the LaunchedEffect below retries.
        }
        inputView.requestFocus()
        inputView.post {
            inputMethodManager?.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // Grab focus and raise the soft keyboard as soon as the PTY becomes active so
    // the user can type immediately after booting. Keyed on the view so focus is
    // restored when the screen re-enters composition after a tab switch.
    LaunchedEffect(state.isTerminalActive, inputView) {
        if (state.isTerminalActive) {
            focusInput()
        }
    }

    // Blinking cursor animation (Termux style block)
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermuxBackground)
    ) {
        TerminalStatusBar(
            isActive = state.isTerminalActive,
            isExtracting = state.isExtractingRootfs,
            onStart = { viewModel.startTerminal(context) },
            onKill = { viewModel.closeTerminal() }
        )

        // Auto-scrolling terminal canvas
        val scrollState = rememberScrollState()
        LaunchedEffect(state.terminalOutput) {
            if (state.terminalOutput.isNotEmpty()) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        // Raw input surface. It renders nothing and keeps no text buffer, so the
        // IME's composition state is never invalidated by us.
        AndroidView(
            factory = { inputView },
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(inputFocusRequester)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TermuxBackground)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (state.isTerminalActive) {
                        focusInput()
                    }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            val idlePrompt = state.terminalOutput.isEmpty() && !state.isTerminalActive

            SelectionContainer {
                Text(
                    // The cursor is concatenated into the output text. A sibling
                    // Text in a Row would be pushed off-screen by the full-width
                    // multi-line output above it.
                    text = buildAnnotatedString {
                        if (idlePrompt) {
                            withStyle(SpanStyle(color = TermuxGreen)) { append("~ $") }
                        } else {
                            append(state.terminalOutput)
                        }
                        if (state.isTerminalActive || idlePrompt) {
                            withStyle(SpanStyle(color = TermuxForeground.copy(alpha = cursorAlpha))) {
                                append("█")
                            }
                        }
                    },
                    color = TermuxForeground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                )
            }
        }

        // Termux-Style Extra Keys Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TermuxToolbar)
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val keyModifier = Modifier
                .weight(1f)
                .height(34.dp)

            TermuxKeyButton("ESC", keyModifier) {
                viewModel.sendEsc()
                focusInput()
            }
            TermuxKeyButton("TAB", keyModifier) {
                viewModel.sendTab()
                focusInput()
            }
            TermuxKeyButton("CTRL", keyModifier) {
                viewModel.sendCtrlC()
                focusInput()
            }
            TermuxKeyButton("/", keyModifier) {
                viewModel.sendRawInput("/")
                focusInput()
            }
            TermuxKeyButton("-", keyModifier) {
                viewModel.sendRawInput("-")
                focusInput()
            }
            TermuxKeyButton("↑", keyModifier) {
                viewModel.sendBytes(byteArrayOf(0x1B, 0x5B, 0x41))
            }
            TermuxKeyButton("CLS", keyModifier) {
                viewModel.clearScreen()
            }
            TermuxKeyButton("↵", keyModifier, isEnter = true) {
                viewModel.sendEnter()
                focusInput()
            }
        }
    }
}

@Composable
private fun TerminalStatusBar(
    isActive: Boolean,
    isExtracting: Boolean,
    onStart: () -> Unit,
    onKill: () -> Unit
) {
    val label = when {
        isActive -> "vportable ~ pts/0"
        isExtracting -> "extracting rootfs..."
        else -> "no session"
    }
    val labelColor = when {
        isActive -> TermuxGreen
        isExtracting -> TermuxAmber
        else -> TermuxRed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(TermuxStatusBar)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (isActive) TermuxGreen else TermuxRed, CircleShape)
            )
            Text(
                text = label,
                color = labelColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }

        when {
            isExtracting -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = TermuxAmber,
                strokeWidth = 2.dp
            )

            isActive -> StatusBarButton(
                label = "KILL",
                background = TermuxKillBackground,
                contentColor = TermuxRed,
                onClick = onKill
            )

            else -> StatusBarButton(
                label = "NEW SESSION",
                background = TermuxEnterBackground,
                contentColor = TermuxGreen,
                onClick = onStart
            )
        }
    }
}

@Composable
private fun StatusBarButton(
    label: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = contentColor,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun TermuxKeyButton(
    label: String,
    modifier: Modifier = Modifier,
    isEnter: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (isEnter) TermuxEnterBackground else TermuxKey)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isEnter) TermuxGreen else TermuxKeyText,
            fontSize = if (isEnter) 13.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
