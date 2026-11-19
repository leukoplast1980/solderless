/*
 * Solderless - JNI entry points of the emulator (see jni.c).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

object NativeVm {
    init {
        System.loadLibrary("solderlessjni")
    }

    /** [hseHz]: crystal on PF0/PF1 (0: none); [lse]: 32.768 kHz crystal on PC14/PC15. */
    @JvmStatic external fun create(deterministic: Boolean, hseHz: Int, lse: Boolean): Long
    @JvmStatic external fun loadFlash(handle: Long, image: ByteArray)
    @JvmStatic external fun copyFlashIfDirty(handle: Long): ByteArray?
    @JvmStatic external fun copyFlash(handle: Long): ByteArray?

    /** Blocks the calling thread, running the chip until [shutdown]. */
    @JvmStatic external fun run(handle: Long)
    @JvmStatic external fun shutdown(handle: Long)
    @JvmStatic external fun pause(handle: Long, paused: Boolean)
    @JvmStatic external fun step(handle: Long)
    /** The reset button (NRST): RAM keeps its contents. */
    @JvmStatic external fun reset(handle: Long)
    /** Power off and on: RAM is cleared. */
    @JvmStatic external fun powerCycle(handle: Long)

    @JvmStatic external fun uartWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun uartRead(handle: Long, buffer: ByteArray): Int
    @JvmStatic external fun waitingForKey(handle: Long): Boolean

    /** Sends one line and waits for Forth: 0 ok, 1 error, 2 timeout, 3 too long. Blocks. */
    @JvmStatic external fun sendLine(handle: Long, line: ByteArray, timeoutMs: Int): Int

    /** See [Snapshot] for the layout. */
    @JvmStatic external fun snapshot(handle: Long): IntArray
    @JvmStatic external fun readMemory(handle: Long, address: Int, length: Int): ByteArray?
    @JvmStatic external fun peekWords(handle: Long, addresses: IntArray): IntArray

    /** Messages from the chip model (one per line), or null. */
    @JvmStatic external fun readLog(handle: Long): String?

    /**
     * Mecrisp-Stellaris' dictionary, starting from the entry stored at
     * [rootPointer]. Returns { names: ByteArray (UTF-8, concatenated),
     * nameLengths: IntArray, headers: IntArray, codes: IntArray,
     * flags: IntArray } in chain order.
     */
    @JvmStatic external fun dictionary(handle: Long, rootPointer: Int): Array<Any>?

    /** The breadboard: 8 ints per attachment (part, role, pin slot, kind, toVcc, ohm, Vf mV, bounce us). */
    @JvmStatic external fun boardSet(handle: Long, attachments: IntArray)
    /** A button's pressed state (0/1) or a pot's wiper in per mille. */
    @JvmStatic external fun boardInput(handle: Long, part: Int, value: Int)
    /** Fills [out] (at least [BoardState.SIZE] ints); starts a new averaging window. */
    @JvmStatic external fun boardRead(handle: Long, out: IntArray)
}
