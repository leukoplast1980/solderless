/*
 * Solderless - plays what piezo and speaker pins do: square waves for PWM,
 * the waveform of edges and DAC samples, a little behind emulated time.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioOut(private val handle: Long) {

    private class Seg(val t: Long, val pwm: Boolean, val a: Long, val b: Long)

    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val rate = 44100
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 4096 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { Log.w("AudioOut", "no audio track", e); return }
        track.play()
        val raw = IntArray(2 + 5 * 1024)
        val pins = HashMap<Int, ArrayDeque<Seg>>()
        val phase = HashMap<Int, Double>()
        val chunk = 441                              // 10 ms
        val buf = ShortArray(chunk)
        val nsPerSample = 1_000_000_000.0 / rate
        var playhead = -1L                           // emulated ns of the next sample
        var lastIn = 0.0; var lastOut = 0.0
        try {
            while (running) {
                val n = NativeVm.audioRead(handle, raw)
                val now = (raw[1].toLong() shl 32) or (raw[0].toLong() and 0xFFFFFFFFL)
                for (i in 0 until n) {
                    val o = 2 + 5 * i
                    val t = (raw[o + 1].toLong() shl 32) or (raw[o].toLong() and 0xFFFFFFFFL)
                    val kind = raw[o + 2]
                    pins.getOrPut(kind shr 8) { ArrayDeque() }
                        .addLast(Seg(t, kind and 255 == 1, raw[o + 3].toLong() and 0xFFFFFFFFL, raw[o + 4].toLong() and 0xFFFFFFFFL))
                }
                if (playhead < 0 || now - playhead > LATENCY_NS * 4) playhead = now - LATENCY_NS
                if (now - playhead < LATENCY_NS) { Thread.sleep(5); continue }
                for (k in 0 until chunk) {
                    val t = playhead + (k * nsPerSample).toLong()
                    var x = 0.0
                    for ((pin, q) in pins) {
                        while (q.size > 1 && q[1].t <= t) q.removeFirst()
                        val s = q.firstOrNull() ?: continue
                        if (s.t > t) continue
                        x += if (s.pwm && s.a > 0) {
                            var ph = (phase[pin] ?: 0.0) + s.a / 1000.0 / rate
                            ph -= kotlin.math.floor(ph)
                            phase[pin] = ph
                            if (ph < s.b / 65536.0) 1.0 else 0.0
                        } else s.a / 3300.0
                    }
                    // Block DC: a pin resting high is silent
                    val y = x - lastIn + 0.995 * lastOut
                    lastIn = x; lastOut = y
                    buf[k] = (y.coerceIn(-1.0, 1.0) * 9000).toInt().toShort()
                }
                playhead += (chunk * nsPerSample).toLong()
                track.write(buf, 0, chunk)
            }
        } catch (_: InterruptedException) {
        } finally {
            track.stop(); track.release()
        }
    }

    companion object {
        private const val LATENCY_NS = 80_000_000L
    }
}
