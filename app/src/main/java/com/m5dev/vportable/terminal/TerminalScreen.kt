package com.m5dev.vportable.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status & Session Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Interactive PTY Terminal",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Text(
                        text = state.statusMessage,
                        color = if (state.isTerminalActive) Color(0xFF69F0AE) else Color.LightGray,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (state.isExtractingRootfs) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        color = Color(0xFF69F0AE),
                        strokeWidth = 2.dp
                    )
                } else if (!state.isTerminalActive) {
                    Button(
                        onClick = { viewModel.startTerminal(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Start Terminal", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = { viewModel.closeTerminal() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Exit", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // Terminal Canvas Display Area (Termux-Style)
        val scrollState = rememberScrollState()
        LaunchedEffect(state.terminalOutput) {
            if (state.terminalOutput.isNotEmpty()) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        // Invisible input field that intercepts character keystrokes directly
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                if (newText.length > oldText.length) {
                    val addedText = newText.substring(oldText.length)
                    viewModel.sendRawInput(addedText)
                } else if (newText.length < oldText.length) {
                    val diffCount = oldText.length - newText.length
                    repeat(diffCount) {
                        viewModel.sendBackspace()
                    }
                }
                textFieldValue = TextFieldValue(text = "", selection = TextRange(0))
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                viewModel.sendEnter()
                textFieldValue = TextFieldValue("", selection = TextRange(0))
            }),
            modifier = Modifier
                .height(0.dp)
                .width(0.dp)
                .focusRequester(focusRequester)
                .focusable()
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0A0A0A), shape = RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF333333), shape = RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (state.isTerminalActive) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }
                .padding(8.dp)
        ) {
            if (state.terminalOutput.isEmpty() && !state.isTerminalActive) {
                Text(
                    text = "Tap 'Start Terminal' to launch interactive Alpine Linux shell (/bin/sh -i)...",
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            } else {
                SelectionContainer {
                    Text(
                        text = state.terminalOutput,
                        color = Color(0xFF69F0AE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }

        // Quick Action Toolbar Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    viewModel.sendCtrlC()
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                enabled = state.isTerminalActive,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Ctrl+C", fontSize = 11.sp, color = Color.White)
            }

            Button(
                onClick = {
                    viewModel.sendTab()
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                enabled = state.isTerminalActive,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Tab", fontSize = 11.sp, color = Color.White)
            }

            Button(
                onClick = {
                    viewModel.sendEsc()
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                enabled = state.isTerminalActive,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("ESC", fontSize = 11.sp, color = Color.White)
            }

            Button(
                onClick = { viewModel.clearScreen() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear", fontSize = 11.sp, color = Color.White)
            }
        }
    }
}
