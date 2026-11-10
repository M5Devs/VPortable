package com.m5dev.vportable.terminal

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Input surface that feeds raw characters straight to the PTY.
 *
 * A terminal has no text buffer for the IME to compose into, so a normal text
 * field is the wrong abstraction: it accumulates characters that then have to be
 * wiped, and wiping the buffer mid-composition makes the IME drop events. This
 * view instead exposes a deliberately empty editable that is never mutated and
 * forwards every committed character and key event to [listener] as raw bytes.
 */
class TerminalInputView(context: Context) : View(context) {

    fun interface Listener {
        fun onInput(bytes: ByteArray)
    }

    var listener: Listener? = null

    // Diffs the IME's composing/commit callbacks so each keystroke is sent once.
    private val committer = TerminalTextCommitter()

    // Never written to: an IME that sees a stable, empty text state keeps
    // delivering commits instead of resetting its composition.
    private val emptyEditable = SpannableStringBuilder()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = null
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) requestFocus()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            val ctrl = TerminalInputEncoder.encodeCtrl(event.unicodeChar.toChar())
            if (ctrl != null) {
                committer.reset()
                dispatch(ctrl)
                return true
            }
        }

        val mapped = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalInputEncoder.ENTER
            KeyEvent.KEYCODE_DEL -> TerminalInputEncoder.BACKSPACE
            KeyEvent.KEYCODE_ESCAPE -> TerminalInputEncoder.ESCAPE
            KeyEvent.KEYCODE_TAB -> TerminalInputEncoder.TAB
            KeyEvent.KEYCODE_DPAD_UP -> TerminalInputEncoder.ARROW_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalInputEncoder.ARROW_DOWN
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalInputEncoder.ARROW_RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalInputEncoder.ARROW_LEFT
            else -> null
        }
        if (mapped != null) {
            committer.reset()
            dispatch(mapped)
            return true
        }

        val unicode = event.unicodeChar
        if (unicode != 0) {
            committer.reset()
            emitText(String(Character.toChars(unicode)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        // Keep the IME from turning Enter into an action: it must reach the shell.
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this)
    }

    private fun dispatch(bytes: ByteArray) {
        Log.d("TerminalInput", "view -> ${bytes.size} bytes")
        listener?.onInput(bytes)
    }

    private fun dispatchAll(chunks: List<ByteArray>) {
        chunks.forEach { dispatch(it) }
    }

    private fun emitText(text: CharSequence) {
        TerminalInputEncoder.encodeText(text).forEach { dispatch(it) }
    }

    private inner class TerminalInputConnection(target: View) :
        BaseInputConnection(target, false) {

        override fun getEditable(): Editable = emptyEditable

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.let { dispatchAll(committer.commit(it)) }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // Called for every keystroke while composing. Only the delta against
            // what was already sent is forwarded, so a prefix is never duplicated.
            text?.let { dispatchAll(committer.setComposing(it)) }
            return true
        }

        override fun finishComposingText(): Boolean {
            dispatchAll(committer.finish())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            committer.reset()
            repeat(beforeLength) { dispatch(TerminalInputEncoder.BACKSPACE) }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int
        ): Boolean {
            committer.reset()
            repeat(beforeLength) { dispatch(TerminalInputEncoder.BACKSPACE) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                committer.reset()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        dispatch(TerminalInputEncoder.ENTER)
                        return true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        dispatch(TerminalInputEncoder.BACKSPACE)
                        return true
                    }
                }
                val unicode = event.unicodeChar
                if (unicode != 0) {
                    emitText(String(Character.toChars(unicode)))
                    return true
                }
            }
            return false
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            committer.reset()
            dispatch(TerminalInputEncoder.ENTER)
            return true
        }
    }
}
