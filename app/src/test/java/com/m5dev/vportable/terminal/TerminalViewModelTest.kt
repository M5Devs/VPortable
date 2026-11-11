package com.m5dev.vportable.terminal

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val application = mock(Application::class.java)
    private val bridge = mock(TerminalBridge::class.java)

    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = TerminalViewModel(application, bridge)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() {
        val state = viewModel.uiState.value
        assertFalse(state.isTerminalActive)
        assertFalse(state.isExtractingRootfs)
        assertEquals("", state.terminalOutput)
    }

    @Test
    fun testClearScreen() {
        viewModel.clearScreen()
        assertEquals("", viewModel.uiState.value.terminalOutput)
    }

    @Test
    fun testCloseTerminal() {
        viewModel.closeTerminal()
        assertFalse(viewModel.uiState.value.isTerminalActive)
    }
}
