/*
 * Solderless - JNI bridge between NativeVm.kt and the emulator (emu/).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Handles are sl_vm pointers stored in a Java long.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "sl/solderless.h"

#define FN(name) Java_io_github_leukoplast1980_solderless_vm_NativeVm_##name

static sl_vm *VM(jlong h) { return (sl_vm *)(intptr_t)h; }

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass cls, jboolean deterministic, jint hse_hz, jboolean lse)
{
    (void)env; (void)cls;
    sl_config cfg = { 0 };
    cfg.deterministic = deterministic ? 1 : 0;
    cfg.hse_hz = (uint32_t)hse_hz;
    cfg.lse_fitted = lse ? 1 : 0;
    return (jlong)(intptr_t)sl_create(&cfg);
}

JNIEXPORT void JNICALL FN(loadFlash)(JNIEnv *env, jclass cls, jlong h, jbyteArray image)
{
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, image);
    jbyte *data = (*env)->GetByteArrayElements(env, image, NULL);
    if (!data) return;
    sl_load_flash(VM(h), (const uint8_t *)data, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, image, data, JNI_ABORT);
}

/* Returns the flash image if it changed since the last call, else null. */
JNIEXPORT jbyteArray JNICALL FN(copyFlashIfDirty)(JNIEnv *env, jclass cls, jlong h)
{
    (void)cls;
    if (!sl_flash_dirty(VM(h))) return NULL;
    uint8_t *buf = malloc(SL_FLASH_BYTES);
    if (!buf) return NULL;
    sl_copy_flash(VM(h), buf);
    jbyteArray out = (*env)->NewByteArray(env, SL_FLASH_BYTES);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, SL_FLASH_BYTES, (const jbyte *)buf);
    free(buf);
    return out;
}

/* The whole flash, changed or not. */
JNIEXPORT jbyteArray JNICALL FN(copyFlash)(JNIEnv *env, jclass cls, jlong h)
{
    (void)cls;
    uint8_t *buf = malloc(SL_FLASH_BYTES);
    if (!buf) return NULL;
    sl_copy_flash(VM(h), buf);
    jbyteArray out = (*env)->NewByteArray(env, SL_FLASH_BYTES);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, SL_FLASH_BYTES, (const jbyte *)buf);
    free(buf);
    return out;
}

/* Blocks: runs guest code on the calling thread until shutdown(). */
JNIEXPORT void JNICALL FN(run)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; sl_run(VM(h)); }
JNIEXPORT void JNICALL FN(shutdown)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; sl_shutdown(VM(h)); }
JNIEXPORT void JNICALL FN(pause)(JNIEnv *env, jclass cls, jlong h, jboolean p) { (void)env; (void)cls; sl_pause(VM(h), p ? 1 : 0); }
JNIEXPORT void JNICALL FN(step)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; sl_step(VM(h)); }
JNIEXPORT void JNICALL FN(reset)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; sl_reset(VM(h)); }
JNIEXPORT void JNICALL FN(powerCycle)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; sl_power_cycle(VM(h)); }

JNIEXPORT jint JNICALL FN(uartWrite)(JNIEnv *env, jclass cls, jlong h, jbyteArray data, jint off, jint len)
{
    (void)cls;
    jbyte *p = (*env)->GetByteArrayElements(env, data, NULL);
    if (!p) return 0;
    size_t n = sl_uart_write(VM(h), (const uint8_t *)p + off, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT jint JNICALL FN(uartRead)(JNIEnv *env, jclass cls, jlong h, jbyteArray buffer)
{
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, buffer);
    uint8_t tmp[4096];
    size_t n = sl_uart_read(VM(h), tmp, (size_t)len < sizeof tmp ? (size_t)len : sizeof tmp);
    if (n) (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)n, (const jbyte *)tmp);
    return (jint)n;
}

JNIEXPORT jboolean JNICALL FN(waitingForKey)(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    return sl_waiting_for_key(VM(h)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL FN(sendLine)(JNIEnv *env, jclass cls, jlong h, jbyteArray line, jint timeout_ms)
{
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, line);
    jbyte *p = (*env)->GetByteArrayElements(env, line, NULL);
    if (!p) return SL_LINE_ERROR;
    int r = sl_send_line(VM(h), (const char *)p, (size_t)len, timeout_ms);
    (*env)->ReleaseByteArrayElements(env, line, p, JNI_ABORT);
    return r;
}

/* Layout: see Snapshot.kt */
#define SNAP_INTS 60

JNIEXPORT jintArray JNICALL FN(snapshot)(JNIEnv *env, jclass cls, jlong h)
{
    (void)cls;
    sl_snapshot s;
    sl_snapshot_get(VM(h), &s);
    jint v[SNAP_INTS];
    for (int i = 0; i < 16; i++) v[i] = (jint)s.r[i];
    v[16] = (jint)s.xpsr; v[17] = (jint)s.msp; v[18] = (jint)s.psp;
    v[19] = (jint)s.primask; v[20] = (jint)s.basepri; v[21] = (jint)s.faultmask; v[22] = (jint)s.control;
    v[23] = (jint)s.cfsr; v[24] = (jint)s.hfsr; v[25] = (jint)s.mmfar; v[26] = (jint)s.bfar; v[27] = (jint)s.fault_pc;
    v[28] = (jint)(uint32_t)s.cycles; v[29] = (jint)(uint32_t)(s.cycles >> 32);
    v[30] = (jint)(uint32_t)s.time_ns; v[31] = (jint)(uint32_t)(s.time_ns >> 32);
    v[32] = (jint)s.hclk; v[33] = (jint)s.sysclk; v[34] = (jint)s.pclk1; v[35] = (jint)s.pclk2;
    v[36] = s.lockup; v[37] = s.sleeping; v[38] = s.waiting_for_key; v[39] = s.paused; v[40] = s.speed_percent;
    for (int i = 0; i < 16; i++) v[41 + i] = (jint)s.fault_r[i];
    v[57] = (jint)s.fault_xpsr; v[58] = s.fault_exc; v[59] = (jint)s.fault_count;
    jintArray out = (*env)->NewIntArray(env, SNAP_INTS);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, SNAP_INTS, v);
    return out;
}

JNIEXPORT jbyteArray JNICALL FN(readMemory)(JNIEnv *env, jclass cls, jlong h, jint addr, jint len)
{
    (void)cls;
    if (len <= 0 || len > 1 << 20) return NULL;
    uint8_t *buf = malloc((size_t)len);
    if (!buf) return NULL;
    sl_read_memory(VM(h), (uint32_t)addr, buf, (size_t)len);
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *)buf);
    free(buf);
    return out;
}

JNIEXPORT jintArray JNICALL FN(peekWords)(JNIEnv *env, jclass cls, jlong h, jintArray addrs)
{
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, addrs);
    jint *a = (*env)->GetIntArrayElements(env, addrs, NULL);
    if (!a) return NULL;
    jint *v = malloc(sizeof(jint) * (size_t)(n ? n : 1));
    sl_peek_words(VM(h), (const uint32_t *)a, (uint32_t *)v, (size_t)n);
    (*env)->ReleaseIntArrayElements(env, addrs, a, JNI_ABORT);
    jintArray out = (*env)->NewIntArray(env, n);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, n, v);
    free(v);
    return out;
}

/* The access log since the last call: out[0] the entries dropped, then 12
 * ints per entry (t_ns low, high, t_last_ns low, high, pc, addr, value,
 * mask, before, after, changed, count). Returns the number of entries. */
JNIEXPORT jint JNICALL FN(accessRead)(JNIEnv *env, jclass cls, jlong h, jintArray out)
{
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, out);
    size_t max = len > 1 ? (size_t)(len - 1) / 12 : 0;
    if (max > 1024) max = 1024;
    static sl_access ev[1024];      /* called from the UI thread only */
    uint32_t lost = 0;
    size_t n = sl_access_read(VM(h), ev, max, &lost);
    jint *o = (*env)->GetIntArrayElements(env, out, NULL);
    if (!o) return 0;
    o[0] = (jint)lost;
    for (size_t i = 0; i < n; i++) {
        jint *p = o + 1 + 12 * i;
        const sl_access *e = &ev[i];
        p[0] = (jint)(uint32_t)e->t_ns; p[1] = (jint)(uint32_t)(e->t_ns >> 32);
        p[2] = (jint)(uint32_t)e->t_last_ns; p[3] = (jint)(uint32_t)(e->t_last_ns >> 32);
        p[4] = (jint)e->pc; p[5] = (jint)e->addr; p[6] = (jint)e->value; p[7] = (jint)e->mask;
        p[8] = (jint)e->before; p[9] = (jint)e->after; p[10] = (jint)e->changed; p[11] = (jint)e->count;
    }
    (*env)->ReleaseIntArrayElements(env, out, o, 0);
    return (jint)n;
}

/* A word write as the CPU would make it; 0 if it went through */
JNIEXPORT jint JNICALL FN(pokeWord)(JNIEnv *env, jclass cls, jlong h, jint addr, jint value)
{
    (void)env; (void)cls;
    return sl_poke32(VM(h), (uint32_t)addr, (uint32_t)value);
}

/* Messages from the chip model, or null if there are none. */
JNIEXPORT jstring JNICALL FN(readLog)(JNIEnv *env, jclass cls, jlong h)
{
    (void)cls;
    char buf[4096];
    size_t n = sl_log_read(VM(h), buf, sizeof buf - 1);
    if (!n) return NULL;
    buf[n] = 0;
    return (*env)->NewStringUTF(env, buf);
}

/*
 * Walks Mecrisp-Stellaris' dictionary in guest memory. Each entry is
 *   link (4 bytes) | flags (2) | name length (1) | name | padding to even | code
 * The chain starts at the word stored at root_ptr (the newest RAM word, or
 * the start of the core dictionary), runs through the core into the flash
 * dictionary, and ends at an erased link ($FFFFFFFF).
 *
 * Returns { names: ByteArray (concatenated), nameLengths: IntArray,
 * headers: IntArray, codes: IntArray, flags: IntArray } in chain order.
 */
JNIEXPORT jobjectArray JNICALL FN(dictionary)(JNIEnv *env, jclass cls, jlong h, jint root_ptr)
{
    (void)cls;
    enum { RAM = 0x20000000, RAM_SIZE = 0x8000, MAX = 3000 };
    uint8_t *flash = malloc(SL_FLASH_BYTES), *ram = malloc(RAM_SIZE);
    char *names = malloc(MAX * 64);
    jint *lens = malloc(sizeof(jint) * MAX), *hdrs = malloc(sizeof(jint) * MAX);
    jint *codes = malloc(sizeof(jint) * MAX), *flags = malloc(sizeof(jint) * MAX);
    jobjectArray result = NULL;
    if (!flash || !ram || !names || !lens || !hdrs || !codes || !flags) goto out;

    sl_read_memory(VM(h), 0, flash, SL_FLASH_BYTES);
    sl_read_memory(VM(h), RAM, ram, RAM_SIZE);

#define AT(a) ((a) < SL_FLASH_BYTES ? flash + (a) : (a) >= RAM && (a) < RAM + RAM_SIZE ? ram + ((a) - RAM) : NULL)
    uint32_t rp = (uint32_t)root_ptr, entry;
    const uint8_t *p = AT(rp);
    if (!p) goto out;
    memcpy(&entry, p, 4);

    int n = 0;
    size_t names_len = 0;
    while (entry != 0xFFFFFFFFu && n < MAX) {
        const uint8_t *e = AT(entry);
        if (!e || !AT(entry + 7)) break;
        uint32_t link;
        memcpy(&link, e, 4);
        uint32_t fl = (uint32_t)e[4] | ((uint32_t)e[5] << 8);
        uint32_t len = e[6];
        if (!AT(entry + 7 + len)) break;
        if (fl != 0xFFFFu && len > 0 && len < 64) {
            memcpy(names + names_len, e + 7, len);
            names_len += len;
            lens[n] = (jint)len;
            hdrs[n] = (jint)entry;
            codes[n] = (jint)((entry + 7 + len + 1) & ~1u);
            flags[n] = (jint)fl;
            n++;
        }
        if (link == entry) break;
        entry = link;
    }
#undef AT

    jclass objc = (*env)->FindClass(env, "java/lang/Object");
    result = (*env)->NewObjectArray(env, 5, objc, NULL);
    if (!result) goto out;
    jbyteArray jn = (*env)->NewByteArray(env, (jsize)names_len);
    (*env)->SetByteArrayRegion(env, jn, 0, (jsize)names_len, (const jbyte *)names);
    jintArray a[4];
    jint *src[4] = { lens, hdrs, codes, flags };
    (*env)->SetObjectArrayElement(env, result, 0, jn);
    for (int i = 0; i < 4; i++) {
        a[i] = (*env)->NewIntArray(env, n);
        (*env)->SetIntArrayRegion(env, a[i], 0, n, src[i]);
        (*env)->SetObjectArrayElement(env, result, i + 1, a[i]);
    }
out:
    free(flash); free(ram); free(names); free(lens); free(hdrs); free(codes); free(flags);
    return result;
}

/* ---- The breadboard ---------------------------------------------------- */

/* att: 10 ints per attachment, in sl_attach's field order */
JNIEXPORT void JNICALL FN(boardSet)(JNIEnv *env, jclass cls, jlong h, jintArray att)
{
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, att) / 10;
    if (n > SL_MAX_ATTACH) n = SL_MAX_ATTACH;
    sl_attach a[SL_MAX_ATTACH];
    jint *v = (*env)->GetIntArrayElements(env, att, NULL);
    for (jsize i = 0; i < n; i++) {
        jint *x = v + 10 * i;
        a[i].part = x[0]; a[i].role = x[1]; a[i].pin = x[2]; a[i].kind = x[3];
        a[i].to_vcc = x[4]; a[i].r_ohm = x[5]; a[i].vf_mv = x[6]; a[i].bounce_us = x[7];
        a[i].dev = x[8]; a[i].addr = x[9];
    }
    (*env)->ReleaseIntArrayElements(env, att, v, JNI_ABORT);
    sl_board_set(VM(h), a, (int)n);
}

/* A bus part: pixels (one byte each) into px; returns its state as text */
JNIEXPORT jstring JNICALL FN(deviceRead)(JNIEnv *env, jclass cls, jlong h, jint part, jbyteArray px)
{
    (void)cls;
    static uint8_t buf[240 * 320 * 2];      /* the largest display: an ST7789, two bytes a pixel */
    char text[1024];
    text[0] = 0;
    size_t n = sl_device_read(VM(h), part, buf, sizeof buf, text, sizeof text);
    if (!n && !text[0]) return NULL;           /* not on the board */
    jsize len = (*env)->GetArrayLength(env, px);
    if ((size_t)len > n) len = (jsize)n;
    if (len > 0) (*env)->SetByteArrayRegion(env, px, 0, len, (const jbyte *)buf);
    return (*env)->NewStringUTF(env, text);
}

/* A rotary encoder turned by some detents */
JNIEXPORT void JNICALL FN(boardTurn)(JNIEnv *env, jclass cls, jlong h, jint part, jint steps)
{
    (void)env; (void)cls;
    sl_board_turn(VM(h), part, steps);
}

/* What a sensor senses, as the user sets it */
JNIEXPORT void JNICALL FN(deviceInput)(JNIEnv *env, jclass cls, jlong h, jint part, jstring what, jdouble value)
{
    (void)cls;
    const char *w = (*env)->GetStringUTFChars(env, what, NULL);
    if (!w) return;
    sl_device_input(VM(h), part, w, value);
    (*env)->ReleaseStringUTFChars(env, what, w);
}

/* A memory part's contents: len bytes at addr into or out of buf */
JNIEXPORT jint JNICALL FN(deviceMem)(JNIEnv *env, jclass cls, jlong h, jint part, jint addr, jbyteArray buf, jint len, jboolean write)
{
    (void)cls;
    jsize cap = (*env)->GetArrayLength(env, buf);
    if (len > cap) len = cap;
    if (len <= 0) return 0;
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    size_t n = sl_device_mem(VM(h), part, (uint32_t)addr, (uint8_t *)b, (size_t)len, write ? 1 : 0);
    (*env)->ReleaseByteArrayElements(env, buf, b, write ? JNI_ABORT : 0);
    return (jint)n;
}

/* A count that changes whenever a memory part's contents do */
JNIEXPORT jint JNICALL FN(deviceGen)(JNIEnv *env, jclass cls, jlong h, jint part)
{
    (void)env; (void)cls;
    return (jint)sl_device_gen(VM(h), part);
}

JNIEXPORT void JNICALL FN(boardInput)(JNIEnv *env, jclass cls, jlong h, jint part, jint value)
{
    (void)env; (void)cls;
    sl_board_input(VM(h), part, value);
}

/* Layout: mv, flags, mode, duty, freq_mhz, pwm_duty (SL_PIN_SLOTS each),
 * current_ua (SL_MAX_ATTACH), time_ns (2 ints). */
#define BOARD_INTS (6 * SL_PIN_SLOTS + SL_MAX_ATTACH + 2)
JNIEXPORT void JNICALL FN(boardRead)(JNIEnv *env, jclass cls, jlong h, jintArray out)
{
    (void)cls;
    if ((*env)->GetArrayLength(env, out) < BOARD_INTS) return;
    sl_board_state st;
    sl_board_read(VM(h), &st);
    jint v[BOARD_INTS];
    for (int i = 0; i < SL_PIN_SLOTS; i++) {
        v[i] = st.mv[i];
        v[SL_PIN_SLOTS + i] = st.flags[i];
        v[2 * SL_PIN_SLOTS + i] = st.mode[i];
        v[3 * SL_PIN_SLOTS + i] = st.duty[i];
        v[4 * SL_PIN_SLOTS + i] = (jint)st.freq_mhz[i];
        v[5 * SL_PIN_SLOTS + i] = st.pwm_duty[i];
    }
    for (int i = 0; i < SL_MAX_ATTACH; i++) v[6 * SL_PIN_SLOTS + i] = st.current_ua[i];
    v[BOARD_INTS - 2] = (jint)(uint32_t)st.time_ns;
    v[BOARD_INTS - 1] = (jint)(uint32_t)(st.time_ns >> 32);
    (*env)->SetIntArrayRegion(env, out, 0, BOARD_INTS, v);
}

/* out[0..1]: emulated time now (ns); then 5 ints per event: time lo, time hi,
 * kind, a, b. Returns the number of events. */
JNIEXPORT jint JNICALL FN(audioRead)(JNIEnv *env, jclass cls, jlong h, jintArray out)
{
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, out);
    size_t max = len > 2 ? (size_t)(len - 2) / 5 : 0;
    if (max > 1024) max = 1024;
    sl_audio_event ev[1024];
    uint64_t now = 0;
    size_t n = sl_audio_read(VM(h), ev, max, &now);
    jint v[2 + 5 * 1024];
    v[0] = (jint)(uint32_t)now; v[1] = (jint)(uint32_t)(now >> 32);
    for (size_t i = 0; i < n; i++) {
        jint *x = v + 2 + 5 * i;
        x[0] = (jint)(uint32_t)ev[i].time_ns; x[1] = (jint)(uint32_t)(ev[i].time_ns >> 32);
        x[2] = ev[i].kind; x[3] = (jint)ev[i].a; x[4] = (jint)ev[i].b;
    }
    (*env)->SetIntArrayRegion(env, out, 0, (jsize)(2 + 5 * n), v);
    return (jint)n;
}

JNIEXPORT void JNICALL FN(tracePins)(JNIEnv *env, jclass cls, jlong h, jintArray slots)
{
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, slots);
    if (n > SL_PIN_SLOTS) n = SL_PIN_SLOTS;
    jint v[SL_PIN_SLOTS];
    (*env)->GetIntArrayRegion(env, slots, 0, n, v);
    int s[SL_PIN_SLOTS];
    for (jsize i = 0; i < n; i++) s[i] = v[i];
    sl_trace_pins(VM(h), s, (int)n);
}

/* Same layout as audioRead */
JNIEXPORT jint JNICALL FN(traceRead)(JNIEnv *env, jclass cls, jlong h, jintArray out)
{
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, out);
    size_t max = len > 2 ? (size_t)(len - 2) / 5 : 0;
    if (max > 4096) max = 4096;
    static sl_audio_event ev[4096];
    static jint v[2 + 5 * 4096];
    uint64_t now = 0;
    size_t n = sl_trace_read(VM(h), ev, max, &now);
    v[0] = (jint)(uint32_t)now; v[1] = (jint)(uint32_t)(now >> 32);
    for (size_t i = 0; i < n; i++) {
        jint *x = v + 2 + 5 * i;
        x[0] = (jint)(uint32_t)ev[i].time_ns; x[1] = (jint)(uint32_t)(ev[i].time_ns >> 32);
        x[2] = ev[i].kind; x[3] = (jint)ev[i].a; x[4] = (jint)ev[i].b;
    }
    (*env)->SetIntArrayRegion(env, out, 0, (jsize)(2 + 5 * n), v);
    return (jint)n;
}

/* The scope's decoder: 6 ints per event — t0 (low, high), length in ns,
 * kind | flags << 8 | bits << 16 | part << 24, value | miso << 16,
 * the pins as bytes (SCL/SCK, SDA/MOSI, MISO, CS; 0xFF: none) */
JNIEXPORT jint JNICALL FN(busRead)(JNIEnv *env, jclass cls, jlong h, jintArray out)
{
    (void)cls;
    jsize len = (*env)->GetArrayLength(env, out);
    size_t max = (size_t)len / 6;
    if (max > 2048) max = 2048;
    static sl_bus_event ev[2048];
    static jint v[6 * 2048];
    size_t n = sl_bus_read(VM(h), ev, max);
    for (size_t i = 0; i < n; i++) {
        const sl_bus_event *e = &ev[i];
        jint *x = v + 6 * i;
        x[0] = (jint)(uint32_t)e->t0_ns; x[1] = (jint)(uint32_t)(e->t0_ns >> 32);
        uint64_t d = e->t1_ns > e->t0_ns ? e->t1_ns - e->t0_ns : 0;
        x[2] = (jint)(d > 0x7FFFFFFF ? 0x7FFFFFFF : d);
        x[3] = (jint)((uint32_t)e->kind | (uint32_t)e->flags << 8 | (uint32_t)e->bits << 16 | (uint32_t)(uint8_t)e->part << 24);
        x[4] = (jint)((uint32_t)e->value | (uint32_t)e->miso << 16);
        x[5] = (jint)((uint32_t)(uint8_t)e->pin[0] | (uint32_t)(uint8_t)e->pin[1] << 8 | (uint32_t)(uint8_t)e->pin2 << 16 | (uint32_t)(uint8_t)e->cs << 24);
    }
    (*env)->SetIntArrayRegion(env, out, 0, (jsize)(6 * n), v);
    return (jint)n;
}
