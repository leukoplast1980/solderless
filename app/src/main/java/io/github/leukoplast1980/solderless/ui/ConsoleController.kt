/*
 * Solderless - the REPL: output, line and raw input, history, and
 * colouring: the input line as you type, the echo of every line sent to
 * Forth, " ok.", errors and warnings.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.forth.ConsoleStyle
import io.github.leukoplast1980.solderless.forth.Dictionary
import io.github.leukoplast1980.solderless.forth.ForthLexer
import io.github.leukoplast1980.solderless.forth.History
import io.github.leukoplast1980.solderless.vm.VmHost
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class ConsoleController(
    private val activity: Activity,
    private val vm: VmHost,
    /** Input text or cursor changed: update the suggestion strip. */
    private val onInputChanged: () -> Unit,
    /** The dictionary was re-read: completion and highlighting can update. */
    private val onDictionaryChanged: () -> Unit,
) : InputTarget {

    private val output: TextView = activity.findViewById(R.id.output)
    private val scroll: ScrollView = activity.findViewById(R.id.scroll)
    val input: EditText = activity.findViewById(R.id.input)
    val rawInput: RawInputView = activity.findViewById(R.id.rawInput)
    private val sendButton: Button = activity.findViewById(R.id.send)

    val history = History(File(activity.filesDir, "history.txt"))

    var rawMode = false
        private set

    /** What has been typed on the current line in raw mode (for suggestions). */
    private val rawLine = StringBuilder()

    private val readBuffer = ByteArray(4096)
    private val received = StringBuilder()
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pending = ByteBuffer.allocate(8192)
    private val chars = CharBuffer.allocate(8192)

    private val highlighter = ForthHighlighter(activity)

    /** Lines sent to Forth whose echo has not shown up yet, oldest first. */
    private val expectedEcho = ArrayDeque<String>()

    /** Where the console line now being written starts in the output. */
    private var lineStart = 0

    /** End of the coloured echo on that line, or -1 if it has none (yet). */
    private var echoEnd = -1

    /** Keep showing the newest output; off while the user reads further up. */
    private var followOutput = true

    /** Set when Forth may have changed its dictionary; re-read when it is idle. */
    private var dictionaryStale = true
    private var dictionaryLoading = false

    init {
        output.setText("", TextView.BufferType.EDITABLE)
        // AppCompat adds an emoji input filter to every text view. While its
        // emoji font loads (or for good, if loading fails) the filter returns
        // the whole source of append(text, start, end) instead of the range,
        // which put every line break into the console twice. The console
        // needs no emoji processing.
        (output as? AppCompatTextView)?.setEmojiCompatEnabled(false)
        // Plain line breaking: the default (high quality) weighs whole
        // paragraphs, which gets slow on long output lines.
        output.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        output.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE

        // New output (after layout, when its height is known) and a smaller
        // console (keyboard opening) keep the end in view while following.
        val follow = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (followOutput) scroll.scrollTo(0, output.bottom)
        }
        // A tap on the output gives the focus back to the input line; a long
        // press still selects text to copy.
        output.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) output.post { if (!output.hasSelection()) focusInput() }
        }
        output.addOnLayoutChangeListener(follow)
        scroll.addOnLayoutChangeListener(follow)
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (scroll.isLaidOut && output.isLaidOut) followOutput = atEnd()
        }

        sendButton.setOnClickListener { sendLine() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) { sendLine(); true } else false
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { recall(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { recall(1); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { sendLine(); true }
                else -> false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                highlightInput()
                onInputChanged()
            }
        })

        rawInput.onText = { text -> sendRaw(text) }
        rawInput.onSpecialKey = { event -> rawSpecialKey(event) }
    }

    // ------------------------------------------------------------------
    // Output

    /**
     * Moves Forth's output into the console. Call regularly on the UI thread.
     * Takes at most MAX_POLL_BYTES per call: a program that prints without
     * end refills the output as fast as it is read, and reading until it is
     * empty would never return. Forth waits while the output buffer is full.
     */
    fun poll() {
        while (true) {
            expectedEcho.addLast(vm.sentLines.poll() ?: break)
            if (expectedEcho.size > MAX_EXPECTED) expectedEcho.removeFirst()
        }
        // One append per poll: every append lays out the console's last line
        // again, and a program printing without newlines makes that line long.
        received.setLength(0)
        var taken = 0
        while (taken < MAX_POLL_BYTES) {
            val n = vm.read(readBuffer)
            if (n <= 0) break
            taken += n
            var off = 0
            while (off < n) {
                val take = minOf(n - off, pending.remaining())
                pending.put(readBuffer, off, take)
                off += take
                pending.flip()
                decoder.decode(pending, chars, false)
                pending.compact()
                chars.flip()
                received.append(chars)
                chars.clear()
            }
        }
        if (received.isNotEmpty()) {
            appendText(received.toString())
            if (followOutput) scroll.post { scroll.scrollTo(0, output.bottom) }
        }

        if (dictionaryStale && !dictionaryLoading && vm.waitingForKey()) {
            dictionaryStale = false
            dictionaryLoading = true
            Thread {
                vm.refreshDictionary()
                activity.runOnUiThread {
                    dictionaryLoading = false
                    highlightInput()
                    onDictionaryChanged()
                }
            }.start()
        }
    }

    /**
     * Appends Forth output; handles backspace (8), drops carriage returns,
     * and colours each line: the echo of a line we sent as soon as it is
     * complete, Forth's messages when the line ends. A line longer than
     * LONG_LINE is broken: every append lays out the line it extends again,
     * and output without line ends would make that ever slower.
     */
    fun appendText(text: String) {
        val buf = output.editableText ?: return
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (c != '\b' && c != '\r' && c != '\n') {
                if (buf.length + (i - start) - lineStart < LONG_LINE) continue
                buf.append(text.substring(start, i))
                start = i
                endLine(buf)
                continue
            }
            // Whole strings, never append(text, start, end): see init.
            if (i > start) buf.append(text.substring(start, i))
            start = i + 1
            when (c) {
                '\b' -> if (buf.length > lineStart) {
                    buf.delete(buf.length - 1, buf.length)
                    if (echoEnd > buf.length) echoEnd = -1
                }
                '\n' -> endLine(buf)
            }
        }
        if (start < text.length) buf.append(text.substring(start))
        matchEcho(buf, lineDone = false)

        if (buf.length > MAX_CHARS) {
            val cut = buf.length - MAX_CHARS * 3 / 4
            buf.delete(0, cut)
            lineStart = (lineStart - cut).coerceAtLeast(0)
            if (echoEnd >= 0) echoEnd = (echoEnd - cut).coerceAtLeast(-1)
        }
    }

    /**
     * Shows messages from the chip model (what a real chip would not tell),
     * each on its own line, before the line Forth is still printing.
     */
    fun appendChipMessages(text: String) {
        val buf = output.editableText ?: return
        val b = android.text.SpannableStringBuilder()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val start = b.length
            b.append("▲ ").append(line).append('\n')
            b.setSpan(
                android.text.style.ForegroundColorSpan(activity.getColor(R.color.chip_message)),
                start, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (b.isEmpty()) return
        buf.insert(lineStart, b)
        lineStart += b.length
        if (echoEnd >= 0) echoEnd += b.length
        if (followOutput) scroll.post { scroll.scrollTo(0, output.bottom) }
    }

    private fun endLine(buf: Editable) {
        matchEcho(buf, lineDone = true)
        val end = buf.length
        buf.append('\n')
        val line = buf.substring(lineStart, end)
        val from = if (echoEnd >= 0) echoEnd - lineStart else 0
        highlighter.applyMessages(buf, lineStart, ConsoleStyle.messages(line, from))
        lineStart = buf.length
        echoEnd = -1
    }

    /**
     * Colours the echo of a line we sent once the current console line
     * starts with it. Usually that is the oldest line still expected; when a
     * line ends without matching it, later ones are tried too, and the ones
     * before a match are given up (Stop or Reset can swallow input).
     */
    private fun matchEcho(buf: Editable, lineDone: Boolean) {
        if (echoEnd >= 0 || expectedEcho.isEmpty()) return
        val line = buf.subSequence(lineStart, buf.length)
        val candidates = if (lineDone) minOf(expectedEcho.size, ECHO_LOOKAHEAD) else 1
        for (k in 0 until candidates) {
            val sent = expectedEcho[k]
            if (!ConsoleStyle.startsWithEcho(line, sent)) continue
            repeat(k + 1) { expectedEcho.removeFirst() }
            highlighter.applyAt(buf, lineStart, ForthLexer.lex(sent, vm.dictionary::lookup))
            echoEnd = lineStart + sent.length
            return
        }
    }

    /** Colours the input line; the word being typed is not marked unknown yet. */
    private fun highlightInput() {
        val text = input.text ?: return
        highlighter.apply(text, ForthLexer.lex(text, vm.dictionary::lookup), typingAt = input.selectionEnd)
    }

    /** Text size of the output and the input line, in sp. */
    fun setTextSizeSp(sp: Float) {
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    fun clear() {
        output.editableText?.clear()
        lineStart = 0
        echoEnd = -1
        expectedEcho.clear()
    }

    fun copyAll() {
        val clip = activity.getSystemService(ClipboardManager::class.java) ?: return
        clip.setPrimaryClip(ClipData.newPlainText("Mecrisp Pocket", output.text))
        Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** The end of the console, for saving between runs. */
    fun savedText(): CharSequence {
        val t = output.text
        return t.subSequence(maxOf(0, t.length - SAVED_CHARS), t.length)
    }

    /**
     * Shows the end of the output and keeps following it. Not
     * fullScroll(FOCUS_DOWN): that also takes the focus from the input line.
     */
    fun scrollToEnd() {
        followOutput = true
        scroll.post { scroll.scrollTo(0, output.bottom) }
    }

    private fun atEnd() = scroll.scrollY + scroll.height - scroll.paddingBottom >=
        output.bottom - output.lineHeight

    // ------------------------------------------------------------------
    // Input

    fun markDictionaryStale() {
        dictionaryStale = true
    }

    fun setRawMode(raw: Boolean) {
        rawMode = raw
        rawLine.setLength(0)
        input.visibility = if (raw) View.GONE else View.VISIBLE
        sendButton.visibility = if (raw) View.GONE else View.VISIBLE
        rawInput.visibility = if (raw) View.VISIBLE else View.GONE
        focusInput()
        onInputChanged()
    }

    fun focusInput() {
        if (rawMode) rawInput.showKeyboard() else input.requestFocus()
    }

    fun hasFocus(): Boolean = input.hasFocus() || rawInput.hasFocus()

    private fun sendLine() {
        val line = input.text.toString()
        val bytes = line.toByteArray(Charsets.UTF_8).size
        if (bytes > VmHost.MAX_LINE_BYTES) {
            // Forth would cut it off without an error: keep it for editing.
            Toast.makeText(activity, activity.getString(R.string.line_too_long, bytes, VmHost.MAX_LINE_BYTES),
                Toast.LENGTH_LONG).show()
            return
        }
        input.text.clear()
        history.add(line)
        vm.sentLines.add(line)
        send(line + "\n")
        markDictionaryStale()
        scrollToEnd()
        input.requestFocus()
    }

    private fun send(text: String) {
        if (!vm.send(text)) Toast.makeText(activity, R.string.input_full, Toast.LENGTH_SHORT).show()
    }

    private fun recall(direction: Int) {
        val text = history.move(direction)
        input.setText(text)
        input.setSelection(text.length)
    }

    private fun sendRaw(text: String) {
        send(text)
        rawLine.append(text)
        onInputChanged()
    }

    private fun rawSpecialKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                vm.sentLines.add(rawLine.toString())
                send("\r")
                scrollToEnd()
                if (rawLine.isNotBlank()) history.add(rawLine.toString())
                rawLine.setLength(0)
                markDictionaryStale()
            }
            KeyEvent.KEYCODE_DEL -> {
                send("\b")
                if (rawLine.isNotEmpty()) rawLine.setLength(rawLine.length - 1)
            }
            KeyEvent.KEYCODE_TAB -> Completion.tab(this)
            else -> return false
        }
        onInputChanged()
        return true
    }

    // ------------------------------------------------------------------
    // InputTarget

    override fun insertText(text: String) {
        if (rawMode) {
            sendRaw(text)
        } else {
            val start = input.selectionStart.coerceAtLeast(0)
            val end = input.selectionEnd.coerceAtLeast(0)
            input.text.replace(minOf(start, end), maxOf(start, end), text)
            input.requestFocus()
        }
    }

    override fun currentToken(): String {
        val text: CharSequence
        val end: Int
        if (rawMode) {
            text = rawLine
            end = rawLine.length
        } else {
            text = input.text
            end = input.selectionStart.coerceIn(0, text.length)
        }
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        return text.subSequence(start, end).toString()
    }

    override fun applyCompletion(token: String, word: String, finished: Boolean) {
        val tail = if (finished) " " else ""
        if (rawMode) {
            sendRaw((if (word.length >= token.length) word.substring(token.length) else "") + tail)
        } else {
            val end = input.selectionStart.coerceIn(0, input.text.length)
            val start = (end - token.length).coerceAtLeast(0)
            input.text.replace(start, end, word + tail)
            input.setSelection(start + word.length + tail.length)
        }
        focusInput()
    }

    override fun completionDictionary(): Dictionary = vm.dictionary

    override fun arrow(keyCode: Int) {
        if (rawMode) return   // Forth's line input has no cursor to move
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> recall(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> recall(1)
            else -> {
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                input.setSelection((input.selectionStart + delta).coerceIn(0, input.text.length))
                input.requestFocus()
            }
        }
    }

    companion object {
        private const val MAX_CHARS = 100_000
        private const val MAX_POLL_BYTES = 8 * 1024
        private const val LONG_LINE = 2000
        private const val SAVED_CHARS = 20_000
        private const val MAX_EXPECTED = 64
        private const val ECHO_LOOKAHEAD = 8
    }
}
