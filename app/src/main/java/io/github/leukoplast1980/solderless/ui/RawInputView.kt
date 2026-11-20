/*
 * Solderless - raw keyboard input: every key goes straight to Forth,
 * like a serial terminal. Uses a TYPE_NULL input connection so keyboards
 * send single key events instead of composing words.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatTextView

class RawInputView(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {

    /** Printable text typed or committed by the keyboard. */
    var onText: ((String) -> Unit)? = null

    /** Special keys (Enter, Del, Tab, Ctrl combinations). Return true if handled. */
    var onSpecialKey: ((KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { showKeyboard() }
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.let { deliverText(it) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(1, beforeLength)) {
                    deliverKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        deliverKey(event) || super.onKeyDown(keyCode, event)

    private fun deliverText(text: String) {
        val enter = text.indexOfAny(charArrayOf('\n', '\r'))
        if (enter < 0) {
            onText?.invoke(text)
        } else {
            if (enter > 0) onText?.invoke(text.substring(0, enter))
            deliverKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            if (enter + 1 < text.length) deliverText(text.substring(enter + 1))
        }
    }

    private fun deliverKey(event: KeyEvent): Boolean {
        if (onSpecialKey?.invoke(event) == true) return true
        if (event.isCtrlPressed || event.isAltPressed) return false
        val c = event.unicodeChar
        if (c == 0) return false
        onText?.invoke(String(Character.toChars(c)))
        return true
    }
}
