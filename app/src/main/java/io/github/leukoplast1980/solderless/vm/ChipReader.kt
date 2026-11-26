/*
 * Solderless - the chip reads that go on whatever tab is shown: the access
 * log (for the Chip tab's Log) and the scope's trace (so the last seconds
 * are there when the Scope opens). Each read waits for the chip to finish
 * the batch of instructions it is running, a few milliseconds while it is
 * busy: done here, on a thread of their own, the UI thread no longer waits.
 * The UI takes what was read with take...() and only draws.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

import android.os.Handler
import android.os.HandlerThread

class ChipReader(private val handle: Long) {

    /** Register writes as NativeVm.accessRead gives them: [lost] then [n] entries of 12 ints */
    class Writes(val lost: Int, val n: Int, val raw: IntArray)

    /** Scope trace events as NativeVm.traceRead gives them (5 ints each, from index 2) */
    class Trace(val n: Int, val raw: IntArray)

    private val thread = HandlerThread("chip-reader").apply { start() }
    private val handler = Handler(thread.looper)

    private val accessBuf = IntArray(1 + 12 * 1024)
    private val traceBuf = IntArray(2 + 5 * TRACE_CHUNK)
    private val busBuf = IntArray(BUS_INTS * BUS_CHUNK)

    private val writes = ArrayList<Writes>()
    private val traces = ArrayList<Trace>()
    private val busEvents = ArrayList<IntArray>()

    /** The emulated time at the last trace read, ns */
    @Volatile var traceNowNs = 0L
        private set

    /** The scope traces pins (set by the Scope), and whether it is on screen (read more often) */
    @Volatile var scopeTracing = false
    @Volatile var scopeVisible = false

    private var running = false
    private var tick = 0

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            tick++
            if (tick % 2 == 0) readWrites()
            if (scopeVisible || (scopeTracing && tick % 4 == 0)) readScope()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Reads while the app is in front */
    fun setRunning(on: Boolean) = handler.post {
        if (on == running) return@post
        running = on
        handler.removeCallbacks(loop)
        if (on) handler.post(loop)
    }

    private fun readWrites() {
        val n = NativeVm.accessRead(handle, accessBuf)
        if (n <= 0 && accessBuf[0] == 0) return
        val copy = accessBuf.copyOf(1 + 12 * maxOf(n, 0))
        synchronized(writes) { writes += Writes(accessBuf[0], maxOf(n, 0), copy) }
    }

    private fun readScope() {
        while (true) {
            val n = NativeVm.traceRead(handle, traceBuf)
            traceNowNs = (traceBuf[1].toLong() shl 32) or (traceBuf[0].toLong() and 0xFFFFFFFFL)
            if (n > 0) {
                val copy = traceBuf.copyOf(2 + 5 * n)
                synchronized(traces) { traces += Trace(n, copy) }
            }
            if (n < TRACE_CHUNK) break
        }
        while (true) {
            val n = NativeVm.busRead(handle, busBuf)
            if (n > 0) {
                val copy = busBuf.copyOf(BUS_INTS * n)
                synchronized(busEvents) { busEvents += copy }
            }
            if (n < BUS_CHUNK) break
        }
    }

    /** The register writes read since the last call, oldest first */
    fun takeWrites(): List<Writes> = synchronized(writes) { ArrayList(writes).also { writes.clear() } }

    /** The scope's trace events read since the last call, oldest first */
    fun takeTraces(): List<Trace> = synchronized(traces) { ArrayList(traces).also { traces.clear() } }

    /** The bus events read since the last call (BUS_INTS ints each), oldest first */
    fun takeBusEvents(): List<IntArray> = synchronized(busEvents) { ArrayList(busEvents).also { busEvents.clear() } }

    companion object {
        private const val TICK_MS = 50L
        const val TRACE_CHUNK = 4096
        private const val BUS_CHUNK = 2048
        /** ints per bus event, as NativeVm.busRead packs them (BusEv.INTS) */
        const val BUS_INTS = 6
    }
}
