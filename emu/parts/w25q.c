/*
 * Solderless - W25Q32/64/128 serial flash (Winbond W25QxxJV datasheets).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Standard SPI only (modes 0 and 3, MSB first): JEDEC ID (9F), manufacturer
 * and device ID (90), release power-down with the device ID (AB), unique ID
 * (4B), read (03) and fast read (0B), write enable and disable (06, 04),
 * the volatile status register write enable (50), status registers 1-3
 * (05/35/15, 01/31/11), page program (02) that only turns 1s into 0s and
 * wraps at the end of its 256-byte page, sector, 32 KB and 64 KB block and
 * chip erase (20, 52, D8, C7/60), power-down (B9), reset (66 99). Erase and
 * program take the datasheet's typical times with BUSY set, and the chip
 * ignores everything but the status reads meanwhile. Write enable clears
 * itself after every program, erase and status write. The block protect
 * bits (BP2-0, TB, SEC, CMP) guard their range. On the module WP and HOLD
 * are tied high, so the status register protection pins have no effect.
 * Not yet: dual and quad modes, suspend and resume, security registers,
 * SFDP, individual block locks.
 */
#include "parts.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SR1_BUSY 0x01u
#define SR1_WEL  0x02u
#define PS_PER_MS 1000000000ull

static const char *name_of(const struct w25q *w)
{
    switch (w->cap) {
    case 0x16: return "W25Q32";
    case 0x18: return "W25Q128";
    default: return "W25Q64";
    }
}

int w25q_init(struct w25q *w, int cap, uint32_t seed)
{
    memset(w, 0, sizeof *w);
    w->cap = cap == 0x16 || cap == 0x18 ? cap : 0x17;
    w->size = 1u << (w->cap - 0x16 + 22);
    w->mem = malloc(w->size);
    w->used = calloc(w->size / 4096, 1);
    if (!w->mem || !w->used) { free(w->mem); free(w->used); w->mem = 0; w->used = 0; return -1; }
    memset(w->mem, 0xFF, w->size);          /* a new chip comes erased */
    for (int i = 0; i < 8; i++) { seed = seed * 1103515245u + 12345u; w->uid[i] = (uint8_t)(seed >> 16); }
    return 0;
}

void w25q_free(struct w25q *w)
{
    free(w->mem); free(w->used);
    w->mem = 0; w->used = 0;
}

static int busy(const struct w25q *w, uint64_t now) { return now < w->busy_until; }

static uint8_t sr1(const struct w25q *w, uint64_t now)
{
    return (uint8_t)((w->sr1 & ~SR1_BUSY) | (busy(w, now) ? SR1_BUSY : 0));
}

/* The protected range from BP2-0, TB, SEC and CMP (datasheet table 7.1.x) */
static void protected(const struct w25q *w, uint32_t *lo, uint32_t *hi)
{
    int bp = (w->sr1 >> 2) & 7, tb = (w->sr1 >> 5) & 1, sec = (w->sr1 >> 6) & 1, cmp = (w->sr2 >> 6) & 1;
    uint32_t len = 0;
    if (bp == 7 && !sec) len = w->size;
    else if (bp && !sec) len = (w->size / 64) << (bp - 1);
    else if (bp && sec) len = 4096u << (bp > 4 ? 3 : bp - 1);
    if (len >= w->size) len = w->size;
    uint32_t a = tb ? 0 : w->size - len, b = tb ? len : w->size;
    if (!cmp) { *lo = a; *hi = b; return; }
    /* complement: the rest of the chip */
    if (len == 0) { *lo = 0; *hi = w->size; }
    else if (len == w->size) { *lo = *hi = 0; }
    else if (tb) { *lo = len; *hi = w->size; }
    else { *lo = 0; *hi = w->size - len; }
}

static int is_protected(const struct w25q *w, uint32_t a, uint32_t n)
{
    uint32_t lo, hi;
    protected(w, &lo, &hi);
    return lo < hi && a < hi && a + n > lo;
}

#define note(w, ...) snprintf((w)->last, sizeof (w)->last, __VA_ARGS__)

static void start_busy(struct w25q *w, uint64_t now, uint64_t ps, const char *what)
{
    w->busy_until = now + ps;
    w->busy_what = what;
    w->last_ps = ps;
}

/* What happened, for the console: 0 if nothing worth saying */
static void say(struct w25q *w, int code) { if (!w->msg) w->msg = code; }

void w25q_select(struct w25q *w, uint64_t now)
{
    (void)now;
    w->n = 0;
    w->cmd = 0;
    w->skip = 0;
}

uint8_t w25q_byte(struct w25q *w, uint8_t b, uint64_t now, int *drive)
{
    *drive = 0;
    int n = w->n++;
    if (w->skip) return 0xFF;
    if (n == 0) {
        w->cmd = b;
        int status_read = b == 0x05 || b == 0x35 || b == 0x15;
        if (w->powered_down && b != 0xAB) { w->skip = 1; say(w, W25Q_MSG_ASLEEP); return 0xFF; }
        if (busy(w, now) && !status_read) { w->skip = 1; w->ignored = b; say(w, W25Q_MSG_BUSY); return 0xFF; }
        if (b != 0x99) w->reset_enabled = b == 0x66 ? 1 : 0;
        switch (b) {
        case 0x05: *drive = 1; return sr1(w, now);
        case 0x35: *drive = 1; return w->sr2;
        case 0x15: *drive = 1; return w->sr3;
        case 0x9F: *drive = 1; return 0xEF;
        case 0x06: case 0x04: case 0x50: case 0xB9: case 0x66: case 0x99: case 0xC7: case 0x60:
        case 0x03: case 0x0B: case 0x02: case 0x20: case 0x52: case 0xD8: case 0x01: case 0x31: case 0x11:
        case 0x90: case 0xAB: case 0x4B:
            return 0xFF;
        default:
            w->skip = 1;
            w->unknown = b;
            say(w, W25Q_MSG_UNKNOWN);
            return 0xFF;
        }
    }
    switch (w->cmd) {
    case 0x05: *drive = 1; return sr1(w, now);
    case 0x35: *drive = 1; return w->sr2;
    case 0x15: *drive = 1; return w->sr3;
    case 0x9F:
        *drive = 1;
        return n == 1 ? 0x40 : n == 2 ? (uint8_t)w->cap : 0xFF;
    case 0x90:
        if (n <= 3) { w->addr = w->addr << 8 | b; if (n < 3) return 0xFF; }
        *drive = 1;
        /* manufacturer and device ID, in the order address bit 0 selects, repeating */
        return (uint8_t)(((n - 3 + (w->addr & 1)) & 1) ? w->cap - 1 : 0xEF);
    case 0xAB:
        if (n < 3) return 0xFF;
        *drive = 1;
        return (uint8_t)(w->cap - 1);
    case 0x4B:
        if (n < 4) return 0xFF;
        *drive = 1;
        return n - 4 < 8 ? w->uid[n - 4] : 0xFF;
    case 0x03: case 0x0B: {
        int dummy = w->cmd == 0x0B;
        if (n <= 3) { w->addr = (w->addr << 8 | b) & 0xFFFFFFu; }
        if (n < 3 + dummy) return 0xFF;
        if (n == 3 + dummy) w->addr &= w->size - 1;
        *drive = 1;
        uint8_t v = w->mem[w->addr];
        w->addr = (w->addr + 1) & (w->size - 1);
        w->reads++;
        return v;
    }
    case 0x02:
        if (n <= 3) { w->addr = (w->addr << 8 | b) & 0xFFFFFFu; if (n == 3) { w->addr &= w->size - 1; w->page_n = 0; } return 0xFF; }
        /* page program: the bytes collect in the page buffer, wrapping within
         * the page; past 256 bytes the later ones replace the earlier */
        {
            uint32_t col = (w->addr + (uint32_t)w->page_n) & 0xFFu;
            w->page[col] = b;
            w->page_seen[col] = 1;
            w->page_n++;
        }
        return 0xFF;
    case 0x20: case 0x52: case 0xD8:
        if (n <= 3) w->addr = (w->addr << 8 | b) & 0xFFFFFFu;
        return 0xFF;
    case 0x01: case 0x31: case 0x11:
        if (n <= 2) w->wr[n - 1] = b;
        w->wr_n = n;
        return 0xFF;
    default:
        return 0xFF;
    }
}

/* CS high: commands that act now, if they got their whole length */
void w25q_deselect(struct w25q *w, uint64_t now)
{
    int n = w->n;
    if (w->skip || n == 0) { memset(w->page_seen, 0, sizeof w->page_seen); return; }
    switch (w->cmd) {
    case 0x06: if (n == 1) w->sr1 |= SR1_WEL; break;
    case 0x04: if (n == 1) w->sr1 &= ~SR1_WEL; break;
    case 0x50: if (n == 1) w->volatile_sr = 1; break;
    case 0xB9: if (n == 1) { w->powered_down = 1; note(w, "power-down"); w->last_ps = 0; } break;
    case 0xAB: if (w->powered_down) { w->powered_down = 0; note(w, "released from power-down"); w->last_ps = 0; } break;
    case 0x99:
        if (n == 1 && w->reset_enabled) {
            w->sr1 &= (uint8_t)~SR1_WEL; w->volatile_sr = 0; w->busy_until = 0;
            note(w, "software reset"); w->last_ps = 0;
        }
        w->reset_enabled = 0;
        break;
    case 0x02:
        if (n < 5) break;
        if (!(w->sr1 & SR1_WEL)) { say(w, W25Q_MSG_NO_WEL); break; }
        if (is_protected(w, w->addr & ~0xFFu, 256)) { say(w, W25Q_MSG_PROTECTED); w->sr1 &= ~SR1_WEL; break; }
        {
            uint32_t base = w->addr & ~0xFFu;
            int cnt = 0, overwrite = 0;
            w->addr &= w->size - 1;
            for (int c = 0; c < 256; c++) {
                if (!w->page_seen[c]) continue;
                uint8_t *m = &w->mem[base + (uint32_t)c];
                if ((*m & w->page[c]) != w->page[c]) overwrite = 1;
                *m &= w->page[c];           /* programming only turns 1s into 0s */
                cnt++;
            }
            w->used[base / 4096] = 1;
            w->gen++;
            if (overwrite) say(w, W25Q_MSG_NOT_ERASED);
            w->prog_addr = w->addr;
            start_busy(w, now, 7 * PS_PER_MS / 10, "page program");
            note(w, "page program at %06X · %d bytes", (unsigned)(w->addr & (w->size - 1)), cnt);
        }
        w->sr1 &= ~SR1_WEL;
        break;
    case 0x20: case 0x52: case 0xD8: {
        if (n != 4) break;
        uint32_t len = w->cmd == 0x20 ? 4096u : w->cmd == 0x52 ? 32768u : 65536u;
        uint32_t a = (w->addr & (w->size - 1)) & ~(len - 1);
        if (!(w->sr1 & SR1_WEL)) { say(w, W25Q_MSG_NO_WEL); break; }
        if (is_protected(w, a, len)) { say(w, W25Q_MSG_PROTECTED); w->sr1 &= ~SR1_WEL; break; }
        memset(w->mem + a, 0xFF, len);
        memset(w->used + a / 4096, 0, len / 4096);
        w->gen++;
        uint64_t t = w->cmd == 0x20 ? 45 * PS_PER_MS : w->cmd == 0x52 ? 120 * PS_PER_MS : 150 * PS_PER_MS;
        start_busy(w, now, t, w->cmd == 0x20 ? "sector erase" : "block erase");
        note(w, "%s erase at %06X", w->cmd == 0x20 ? "4 KB" : w->cmd == 0x52 ? "32 KB" : "64 KB", (unsigned)a);
        w->sr1 &= ~SR1_WEL;
        break;
    }
    case 0xC7: case 0x60: {
        if (n != 1) break;
        if (!(w->sr1 & SR1_WEL)) { say(w, W25Q_MSG_NO_WEL); break; }
        uint32_t lo, hi;
        protected(w, &lo, &hi);
        if (lo < hi) { say(w, W25Q_MSG_PROTECTED); w->sr1 &= ~SR1_WEL; break; }
        memset(w->mem, 0xFF, w->size);
        memset(w->used, 0, w->size / 4096);
        w->gen++;
        /* 10 s for 32 Mbit, 20 s for 64, 40 s for 128 */
        start_busy(w, now, (uint64_t)10000 * PS_PER_MS << (w->cap - 0x16), "chip erase");
        note(w, "chip erase");
        w->sr1 &= ~SR1_WEL;
        break;
    }
    case 0x01: case 0x31: case 0x11: {
        if (n < 2) break;
        int vol = w->volatile_sr;
        if (!vol && !(w->sr1 & SR1_WEL)) { say(w, W25Q_MSG_NO_WEL); break; }
        if (w->cmd == 0x01) {
            w->sr1 = (uint8_t)((w->sr1 & 0x03u) | (w->wr[0] & 0xFCu));
            if (w->wr_n >= 2) w->sr2 = (uint8_t)((w->sr2 & 0x80u) | (w->wr[1] & 0x7Bu));
        } else if (w->cmd == 0x31) w->sr2 = (uint8_t)((w->sr2 & 0x80u) | (w->wr[0] & 0x7Bu));
        else w->sr3 = (uint8_t)(w->wr[0] & 0x64u);
        if (!vol) start_busy(w, now, 10 * PS_PER_MS, "status register write");
        note(w, "status register write"); if (vol) w->last_ps = 0;
        w->sr1 &= ~SR1_WEL;
        w->volatile_sr = 0;
        break;
    }
    default:
        break;
    }
    memset(w->page_seen, 0, sizeof w->page_seen);
}

int w25q_message(struct w25q *w, char *out, size_t len)
{
    int m = w->msg;
    w->msg = 0;
    const char *nm = name_of(w);
    switch (m) {
    case W25Q_MSG_BUSY:
        snprintf(out, len, "%s: command %02X ignored, still busy with a %s. Poll BUSY (bit 0 of status register 1, "
                 "command 05) until it is 0", nm, w->ignored, w->busy_what ? w->busy_what : "write");
        return 1;
    case W25Q_MSG_NO_WEL:
        snprintf(out, len, "%s: command %02X ignored: write enable (06) was not sent first, so WEL in status register 1 "
                 "is 0. It clears itself after every program and erase", nm, w->cmd);
        return 1;
    case W25Q_MSG_PROTECTED:
        snprintf(out, len, "%s: command %02X ignored: that area is protected by BP2-0, TB, SEC and CMP in the status "
                 "registers (BP = %u)", nm, w->cmd, (w->sr1 >> 2) & 7u);
        return 1;
    case W25Q_MSG_NOT_ERASED:
        snprintf(out, len, "%s: page program over bytes that were not erased: only 1s can become 0s, so the result is "
                 "the old and the new data ANDed. Erase the sector (20) first", nm);
        return 1;
    case W25Q_MSG_ASLEEP:
        snprintf(out, len, "%s is powered down (B9): it ignores everything but release power-down (AB)", nm);
        return 1;
    case W25Q_MSG_UNKNOWN:
        snprintf(out, len, "%s: command %02X is not emulated (dual and quad modes, suspend, security registers are not "
                 "there yet)", nm, w->unknown);
        return 1;
    default:
        return 0;
    }
}

size_t w25q_mem(struct w25q *w, uint32_t addr, uint8_t *buf, size_t len, int write)
{
    if (!w->mem || addr >= w->size) return 0;
    if (len > w->size - addr) len = w->size - addr;
    if (write) {
        memcpy(w->mem + addr, buf, len);
        for (uint32_t s = addr / 4096; s <= (uint32_t)((addr + len - 1) / 4096); s++) {
            const uint8_t *p = w->mem + s * 4096u;
            int any = 0;
            for (int k = 0; k < 4096 && !any; k++) any = p[k] != 0xFF;
            w->used[s] = (uint8_t)any;
        }
        w->gen++;
    } else memcpy(buf, w->mem + addr, len);
    return len;
}

size_t w25q_text(struct w25q *w, uint64_t now, char *out, size_t len)
{
    size_t n = 0;
#define ADD(...) do { if (n < len) { int k_ = snprintf(out + n, len - n, __VA_ARGS__); if (k_ > 0) n += (size_t)k_; } } while (0)
    ADD("Chip\t%s · %u MB · JEDEC EF 40 %02X\n", name_of(w), (unsigned)(w->size >> 20), w->cap);
    ADD("Status\tBUSY %d · WEL %d · BP %u%u%u%s%s%s\n", busy(w, now), (w->sr1 >> 1) & 1,
        (w->sr1 >> 4) & 1u, (w->sr1 >> 3) & 1u, (w->sr1 >> 2) & 1u,
        (w->sr1 & 0x20u) ? " · TB" : "", (w->sr1 & 0x40u) ? " · SEC" : "", (w->sr2 & 0x40u) ? " · CMP" : "");
    if (busy(w, now)) ADD("Busy\t%s, %.0f ms left\n", w->busy_what, (double)(w->busy_until - now) / 1e9);
    if (w->powered_down) ADD("Power\tpowered down (B9)\n");
    if (w->last[0]) {
        if (w->last_ps >= 1000000000000ull) ADD("Last\t%s · %.3g s\n", w->last, (double)w->last_ps / 1e12);
        else if (w->last_ps) ADD("Last\t%s · %.3g ms\n", w->last, (double)w->last_ps / 1e9);
        else ADD("Last\t%s\n", w->last);
    }
    /* Written areas: runs of bytes that are not FF, close ones merged;
     * worked out again only after the contents changed */
    if (!w->runs_valid || w->runs_gen != w->gen) {
        size_t m = 0, cap = sizeof w->runs;
        int runs = 0, more = 0, open = 0;
        uint32_t a = 0, b = 0;
#define RUN(...) do { if (m < cap) { int k_ = snprintf(w->runs + m, cap - m, __VA_ARGS__); if (k_ > 0) m += (size_t)k_; } } while (0)
        for (uint32_t sct = 0; sct < w->size / 4096; sct++) {
            if (!w->used[sct]) continue;
            const uint8_t *p = w->mem + sct * 4096u;
            for (uint32_t k = 0; k < 4096; k++) {
                if (p[k] == 0xFF) continue;
                uint32_t x = sct * 4096u + k;
                if (open && x <= b + 64) { b = x; continue; }
                if (open) { if (runs < 6) RUN("Written\t%06X–%06X\t%u bytes\n", a, b, b - a + 1); else more++; runs++; }
                a = b = x; open = 1;
            }
        }
        if (open) { if (runs < 6) RUN("Written\t%06X–%06X\t%u bytes\n", a, b, b - a + 1); else more++; runs++; }
        if (more) RUN("Written\tand %d more\t\n", more);
        if (!runs) RUN("Written\tnothing yet: every byte reads FF\t\n");
#undef RUN
        w->runs[cap - 1] = 0;
        w->runs_gen = w->gen;
        w->runs_valid = 1;
    }
    ADD("%s", w->runs);
#undef ADD
    return n;
}
