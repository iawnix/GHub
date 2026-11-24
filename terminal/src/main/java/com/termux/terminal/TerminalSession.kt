// SPDX-License-Identifier: GPL-3.0-only
package com.termux.terminal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Semaphore

/** Replaces Termux's JNI/local-process session; all I/O belongs to the remote PTY. */
interface TerminalBackend {
    fun write(bytes: ByteArray)
    fun resize(columns: Int, rows: Int)
    fun close()
}

class TerminalSession(private val backend: TerminalBackend, private val client: TerminalSessionClient) : TerminalOutput() {
    private val main = Handler(Looper.getMainLooper())
    private val pendingReads = Semaphore(16)
    private var emulator: TerminalEmulator? = null
    @Volatile private var running = true
    private val backlog = ArrayDeque<ByteArray>()
    private var backlogSize = 0
    var onChanged: (() -> Unit)? = null

    fun getEmulator() = emulator
    fun getTitle(): String? = emulator?.title
    fun isRunning() = running
    fun getExitStatus() = if (running) 0 else -1
    fun getPid() = -1
    fun getCwd(): String? = null

    fun updateSize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        if (emulator == null) {
            emulator = TerminalEmulator(this, columns, rows, cellWidth, cellHeight, 5000, client)
            while (backlog.isNotEmpty()) backlog.removeFirst().let { emulator!!.append(it, it.size) }
            backlogSize = 0
        } else emulator!!.resize(columns, rows, cellWidth, cellHeight)
        backend.resize(columns, rows)
    }

    /** Caller provides ordered bytes. Parsing and drawing always stay on the UI thread. */
    fun append(bytes: ByteArray) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Apply backpressure to SSH instead of accumulating unbounded Handler messages.
            pendingReads.acquire()
            if (!main.post { try { append(bytes) } finally { pendingReads.release() } }) pendingReads.release()
            return
        }
        val term = emulator
        if (term == null) {
            backlog.addLast(bytes); backlogSize += bytes.size
            while (backlogSize > 262144 && backlog.isNotEmpty()) backlogSize -= backlog.removeFirst().size
        } else term.append(bytes, bytes.size)
        client.onTextChanged(this); onChanged?.invoke()
    }

    fun markFinished() { main.post { running = false; client.onSessionFinished(this); onChanged?.invoke() } }
    override fun write(data: ByteArray, offset: Int, count: Int) { if (running) backend.write(data.copyOfRange(offset, offset + count)) }
    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) = write((if (prependEscape) "\u001b" else "") + String(Character.toChars(codePoint)))
    fun finishIfRunning() { running = false; backend.close() }
    fun reset() { emulator?.reset(); onChanged?.invoke() }
    override fun titleChanged(oldTitle: String?, newTitle: String?) = client.onTitleChanged(this)
    override fun onCopyTextToClipboard(text: String?) = client.onCopyTextToClipboard(this, text.orEmpty())
    override fun onPasteTextFromClipboard() = client.onPasteTextFromClipboard(this)
    override fun onBell() = client.onBell(this)
    override fun onColorsChanged() { client.onColorsChanged(this); onChanged?.invoke() }
}
