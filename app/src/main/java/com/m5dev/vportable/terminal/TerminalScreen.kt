package com.m5dev.vportable.terminal

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Blinking cursor animation (Termux style █)
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
            .background(Color(0xFF000000))
    ) {
        // Sleek Minimal Header (Termux / Pro Console Style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (state.isTerminalActive) Color(0xFF00FF66) else Color(0xFFFF5252),
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (state.isTerminalActive) "VPortable Terminal (Alpine Linux v3.24)" else "Terminal Offline",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }

            if (state.isExtractingRootfs) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color(0xFF00FF66),
                    strokeWidth = 2.dp
                )
            } else if (!state.isTerminalActive) {
                Button(
                    onClick = { viewModel.startTerminal(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Boot Alpine", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                Button(
                    onClick = { viewModel.closeTerminal() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Exit", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Full-Screen Terminal Canvas Area
        val scrollState = rememberScrollState()
        LaunchedEffect(state.terminalOutput) {
            if (state.terminalOutput.isNotEmpty()) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        // Hidden input bridge capturing raw keystrokes without blocking IME
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                if (newText.length > oldText.length) {
                    val added = newText.substring(oldText.length)
                    viewModel.sendRawInput(added)
                } else if (newText.length < oldText.length) {
                    val diff = oldText.length - newText.length
                    repeat(diff) {
                        viewModel.sendBackspace()
                    }
                }
                textFieldValue = TextFieldValue("", selection = TextRange(0))
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.None,
                autoCorrect = false
            ),
            keyboardActions = KeyboardActions(onAny = {
                viewModel.sendEnter()
            }),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester)
        )

        // Main Terminal Text Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (state.isTerminalActive) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (state.terminalOutput.isEmpty() && !state.isTerminalActive) {
                Text(
                    text = "VPortable Container Runtime\nTap 'Boot Alpine' above to launch interactive rootfs shell...",
                    color = Color.DarkGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            } else {
                SelectionContainer {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = state.terminalOutput,
                            color = Color(0xFF00FF66), // Termux Matrix Green
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        if (state.isTerminalActive) {
                            Text(
                                text = "█",
                                color = Color(0xFF00FF66).copy(alpha = cursorAlpha),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // Termux-Style Extra Keys Toolbar (Sits directly above keyboard)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141414))
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val keyModifier = Modifier
                .weight(1f)
                .height(34.dp)

            TermuxKeyButton("ESC", keyModifier) {
                viewModel.sendEsc()
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            TermuxKeyButton("TAB", keyModifier) {
                viewModel.sendTab()
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            TermuxKeyButton("CTRL", keyModifier) {
                viewModel.sendCtrlC()
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            TermuxKeyButton("/", keyModifier) {
                viewModel.sendRawInput("/")
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            TermuxKeyButton("-", keyModifier) {
                viewModel.sendRawInput("-")
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            TermuxKeyButton("↑", keyModifier) {
                viewModel.sendBytes(byteArrayOf(0x1B, 0x5B, 0x41)) // Up Arrow ANSI
            }
            TermuxKeyButton("↓", keyModifier) {
                viewModel.sendBytes(byteArrayOf(0x1B, 0x5B, 0x42)) // Down Arrow ANSI
            }
            TermuxKeyButton("CLS", keyModifier) {
                viewModel.clearScreen()
            }
        }
    }
}

@Composable
private fun TermuxKeyButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF222222),
            contentColor = Color(0xFFE0E0E0)
        ),
        shape = RoundedCornerShape(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
