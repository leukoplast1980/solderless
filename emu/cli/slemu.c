/*
 * Solderless - command-line runner for the emulated G431.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 *   slemu [options] firmware.bin
 *     -d            deterministic: no real-time pacing (fast, reproducible)
 *     -f FILE       flash file: loaded instead of the firmware if it exists,
 *                   saved on exit
 *     -s FILE       send FILE line by line, like e4thcom, then exit
 *     -e LINE       send LINE (repeatable), then exit
 *     -k            keep going after a line that does not answer " ok."
 *     -t MS         per-line timeout (default 10000)
 *     -q            quiet: do not echo the boot banner
 *     -x ADDR,LEN   at exit, dump LEN bytes of memory from ADDR (hex) to stderr
 *
 * Without -s or -e it is an interactive terminal on stdin/stdout.
 */
#define _DEFAULT_SOURCE
#define _POSIX_C_SOURCE 200809L
#include "sl/solderless.h"

#include <pthread.h>
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static sl_vm *vm;
static volatile int done;
static int timeout = 10000;     /* per line, ms (-t, #!timeout) */

static void *vm_thread(void *arg) { (void)arg; sl_run(vm); return NULL; }

static void drain(void)
{
    uint8_t buf[4096];
    size_t n;
    while ((n = sl_uart_read(vm, buf, sizeof buf)) > 0) fwrite(buf, 1, n, stdout);
    fflush(stdout);
    char log[4096];
    while ((n = sl_log_read(vm, log, sizeof log)) > 0) {
        fputs("\n[chip] ", stderr);
        for (size_t i = 0; i < n; i++) {
            fputc(log[i], stderr);
            if (log[i] == '\n' && i + 1 < n) fputs("[chip] ", stderr);
        }
    }
}

static void *reader_thread(void *arg)
{
    (void)arg;
    uint8_t buf[4096];
    while (!done) {
        size_t n = sl_uart_read_wait(vm, buf, sizeof buf, 50);
        if (n) { fwrite(buf, 1, n, stdout); fflush(stdout); }
        char log[4096];
        size_t m = sl_log_read(vm, log, sizeof log);
        if (m) { fputs("[chip] ", stderr); fwrite(log, 1, m, stderr); }
    }
    return NULL;
}

static sl_attach board[SL_MAX_ATTACH];
static int nboard;

static int parse_pin(const char *t)
{
    if (t[0] != 'P' || t[1] < 'A' || t[1] > 'G') return -1;
    return (t[1] - 'A') * 16 + atoi(t + 2);
}

static void print_pins(void)
{
    sl_board_state st;
    sl_board_read(vm, &st);
    static const char *const modes[4] = { "input", "output", "alternate", "analog" };
    for (int p = 0; p < SL_PIN_SLOTS; p++) {
        if (!(st.flags[p] & SL_PIN_USED)) continue;
        printf("P%c%d %-9s ", 'A' + p / 16, p % 16, modes[st.mode[p]]);
        if (st.flags[p] & SL_PIN_FLOATING) printf("floating");
        else if (st.flags[p] & SL_PIN_PWM) printf("PWM %u.%03u Hz duty %u/1000", st.freq_mhz[p] / 1000, st.freq_mhz[p] % 1000, st.duty[p]);
        else printf("%d mV %s", st.mv[p], (st.flags[p] & SL_PIN_UNDEFINED) ? "undefined" : (st.flags[p] & SL_PIN_HIGH) ? "high" : "low");
        if (st.flags[p] & SL_PIN_SHORT) printf(" SHORT");
        for (int i = 0; i < nboard; i++)
            if (board[i].pin == p && board[i].kind == SL_ATT_LED) printf("  LED %d: %d.%03d mA", board[i].part, st.current_ua[i] / 1000, st.current_ua[i] % 1000);
        printf("\n");
    }
    fflush(stdout);
}

/* Handles a "#!" line; returns nonzero on a syntax error. */
static int directive(const char *line)
{
    char cmd[16] = "", a1[16] = "", a2[16] = "", a3[16] = "";
    int n = sscanf(line + 2, "%15s %15s %15s %15s", cmd, a1, a2, a3);
    if (n < 1) return 1;
    if (!strcmp(cmd, "clear")) { nboard = 0; sl_board_set(vm, board, 0); return 0; }
    if (!strcmp(cmd, "powercycle")) {           /* the supply off and on: chip and parts */
        sl_power_cycle(vm);
        sl_sync(vm);                            /* else the prompt from before could pass for the new one */
        sl_wait_idle(vm, 5000);
        return 0;
    }
    if (!strcmp(cmd, "pins")) { print_pins(); return 0; }
    if (!strcmp(cmd, "dpeek") && n >= 2) {
        /* #!dpeek ADDR...: words read as a debugger would (the Registers view) */
        uint32_t addrs[3], out[3];
        int k = 0;
        if (n >= 2) addrs[k++] = (uint32_t)strtoul(a1, NULL, 16);
        if (n >= 3) addrs[k++] = (uint32_t)strtoul(a2, NULL, 16);
        if (n >= 4) addrs[k++] = (uint32_t)strtoul(a3, NULL, 16);
        sl_peek_words(vm, addrs, out, (size_t)k);
        for (int i = 0; i < k; i++) printf("%08X: %08X\n", addrs[i], out[i]);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "timeout") && n >= 2) { timeout = atoi(a1); return 0; }   /* #!timeout MS: per line from here on */
    if (!strcmp(cmd, "fault")) {
        /* #!fault: the last fault, as the fault explainer gets it */
        sl_snapshot s;
        sl_snapshot_get(vm, &s);
        if (!s.fault_count) { printf("no fault\n"); fflush(stdout); return 0; }
        printf("exception %d at %08X  CFSR %08X HFSR %08X BFAR %08X\n", s.fault_exc, s.fault_r[15], s.cfsr, s.hfsr, s.bfar);
        for (int i = 0; i < 16; i++) printf("%s%-3s %08X", i % 4 ? "  " : "", (const char *[]){ "r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7",
                                       "r8", "r9", "r10", "r11", "r12", "sp", "lr", "pc" }[i], s.fault_r[i]), i % 4 == 3 ? printf("\n") : 0;
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "writes")) {
        /* #!writes: the register writes since the last time (the Chip tab's Log) */
        static sl_access ev[1024];
        uint32_t lost;
        size_t m = sl_access_read(vm, ev, 1024, &lost);
        for (size_t i = 0; i < m; i++) {
            const sl_access *e = &ev[i];
            if (e->pc == SL_ACCESS_DEBUG) printf("debug   ");
            else printf("%08X", e->pc);
            printf(" %08X %08X -> %08X", e->addr, e->before, e->after);
            if (e->count > 1) printf(" x%u changed %08X", e->count, e->changed);
            if (e->before == e->after && e->count == 1) printf(" (wrote %08X)", e->value);
            printf("\n");
        }
        if (lost) printf("%u lost\n", lost);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "dpoke") && n >= 3) {
        /* #!dpoke ADDR VALUE: a write as the CPU would make it (the Registers view's Write) */
        if (sl_poke32(vm, (uint32_t)strtoul(a1, NULL, 16), (uint32_t)strtoul(a2, NULL, 16))) printf("dpoke: bus error\n");
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "trace")) {
        int slots[16], k = 0;
        char tmp[256]; strncpy(tmp, line + 8, sizeof tmp - 1); tmp[sizeof tmp - 1] = 0;
        for (char *t = strtok(tmp, " "); t && k < 16; t = strtok(NULL, " ")) slots[k++] = parse_pin(t);
        sl_trace_pins(vm, slots, k);
        return 0;
    }
    if (!strcmp(cmd, "scope")) {
        sl_audio_event ev[256];
        uint64_t now;
        size_t m = sl_trace_read(vm, ev, 256, &now);
        uint64_t t0 = m ? ev[0].time_ns : 0;
        for (size_t i = 0; i < m; i++) {
            int slot = (ev[i].kind >> 8) & 255;
            printf("+%llu us P%c%d%s ", (unsigned long long)((ev[i].time_ns - t0) / 1000), 'A' + slot / 16, slot % 16,
                   (ev[i].kind & SL_TRACE_BUSY) ? " (fast)" : "");
            if ((ev[i].kind & 255) == SL_AUDIO_PWM) printf("PWM %u Hz duty %u/1000\n", ev[i].a / 1000, (ev[i].b * 1000 + 0x8000) >> 16);
            else printf("%d mV\n", (int)ev[i].a);
        }
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "bus")) {
        /* #!bus: what the scope's decoder saw on the traced pins since the last time */
        static sl_bus_event ev[512];
        size_t m = sl_bus_read(vm, ev, 512);
        uint64_t t0 = m ? ev[0].t0_ns : 0;
        for (size_t i = 0; i < m; i++) {
            const sl_bus_event *e = &ev[i];
            printf("+%llu.%01llu us %s ", (unsigned long long)((e->t0_ns - t0) / 1000), (unsigned long long)((e->t0_ns - t0) % 1000 / 100),
                   (e->flags & SL_BUS_HW) ? "hw" : "bb");
            switch (e->kind) {
            case SL_BUS_I2C_START: printf("S\n"); break;
            case SL_BUS_I2C_STOP: printf("P\n"); break;
            case SL_BUS_I2C_ADDR: printf("addr %02X %c %s part %d\n", e->value >> 1, (e->value & 1) ? 'R' : 'W', e->miso ? "ack" : "nack", e->part); break;
            case SL_BUS_I2C_BYTE: printf("%s %02X %s\n", (e->flags & SL_BUS_READ) ? "read" : "byte", e->value, e->miso ? "ack" : "nack"); break;
            case SL_BUS_SPI_BYTE: printf("spi %02X/%02X%s mode %d part %d len %llu ns\n", e->value, e->miso,
                                         (e->flags & SL_BUS_DRIVEN) ? "" : " (MISO not driven)", e->flags & 3, e->part,
                                         (unsigned long long)(e->t1_ns - e->t0_ns)); break;
            case SL_BUS_SPI_CS: printf("cs part %d %s\n", e->part, e->value ? "selected" : "released"); break;
            case SL_BUS_LOST: printf("lost %u\n", (unsigned)e->miso << 16 | e->value); break;
            default: printf("?\n"); break;
            }
        }
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "audio")) {
        sl_audio_event ev[64];
        uint64_t now;
        size_t m = sl_audio_read(vm, ev, 64, &now);
        for (size_t i = 0; i < m; i++) {
            int slot = ev[i].kind >> 8;
            printf("P%c%d ", 'A' + slot / 16, slot % 16);
            if ((ev[i].kind & 255) == SL_AUDIO_PWM) printf("PWM %u Hz duty %u/1000\n", ev[i].a / 1000, (ev[i].b * 1000 + 0x8000) >> 16);
            else printf("level %u mV\n", ev[i].a);
        }
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "input") && n >= 3) { sl_board_input(vm, atoi(a1), atoi(a2)); return 0; }
    if (!strcmp(cmd, "attach") && n >= 4 && (!strcmp(a2, "oled") || !strcmp(a2, "bmp280") || !strcmp(a2, "scd41"))
        && nboard + 2 <= SL_MAX_ATTACH) {
        /* #!attach N oled|bmp280|scd41 SCL SDA [addr=0x3D] [pull=OHM]: an I2C module with its pull-ups
         * (OLED 4.7 kohm at 0x3C, BMP280 10 kohm at 0x76, SCD41 10 kohm at 0x62) */
        char a4[16] = "";
        sscanf(line + 2, "%*s %*s %*s %*s %15s", a4);
        int scl = parse_pin(a3), sda = parse_pin(a4);
        if (scl < 0 || sda < 0) return 1;
        int dev = !strcmp(a2, "bmp280") ? SL_DEV_BMP280 : !strcmp(a2, "scd41") ? SL_DEV_SCD41 : SL_DEV_SSD1306;
        int addr = dev == SL_DEV_BMP280 ? 0x76 : dev == SL_DEV_SCD41 ? 0x62 : 0x3C;
        int pull = dev == SL_DEV_SSD1306 ? 4700 : 10000;
        const char *q = strstr(line, "addr=");
        if (q) addr = (int)strtol(q + 5, NULL, 0);
        q = strstr(line, "pull=");
        if (q) pull = atoi(q + 5);
        for (int k = 0; k < 2; k++) {
            sl_attach *a = &board[nboard++];
            memset(a, 0, sizeof *a);
            a->part = atoi(a1); a->kind = SL_ATT_DEVICE; a->dev = dev; a->addr = addr;
            a->role = k ? SL_SIG_SDA : SL_SIG_SCL; a->pin = k ? sda : scl; a->r_ohm = pull;
        }
        sl_board_set(vm, board, nboard);
        return 0;
    }
    if (!strcmp(cmd, "attach") && n >= 4 && !strcmp(a2, "flash") && nboard + 4 <= SL_MAX_ATTACH) {
        /* #!attach N flash CS CLK DI DO [size=32|64|128]: a W25Q module (WP and HOLD tied high) */
        char p4[16] = "", p5[16] = "", p6[16] = "";
        sscanf(line + 2, "%*s %*s %*s %*s %15s %15s %15s", p4, p5, p6);
        int pins[4] = { parse_pin(a3), parse_pin(p4), parse_pin(p5), parse_pin(p6) };
        static const int roles[4] = { SL_SIG_CS, SL_SIG_SCK, SL_SIG_MOSI, SL_SIG_MISO };
        int cap = 0x17;
        const char *q = strstr(line, "size=");
        if (q) cap = atoi(q + 5) == 32 ? 0x16 : atoi(q + 5) == 128 ? 0x18 : 0x17;
        for (int k = 0; k < 4; k++) {
            if (pins[k] < 0) return 1;
            sl_attach *a = &board[nboard++];
            memset(a, 0, sizeof *a);
            a->part = atoi(a1); a->kind = SL_ATT_DEVICE; a->dev = SL_DEV_W25Q; a->addr = cap;
            a->role = roles[k]; a->pin = pins[k]; a->r_ohm = roles[k] == SL_SIG_CS ? 10000 : 0;   /* the module pulls CS up */
        }
        sl_board_set(vm, board, nboard);
        return 0;
    }
    if (!strcmp(cmd, "attach") && n >= 4 && !strcmp(a2, "matrix") && nboard + 3 <= SL_MAX_ATTACH) {
        /* #!attach N matrix CS CLK DIN [chips=4]: MAX7219 8x8 module(s) */
        char p4[16] = "", p5[16] = "";
        sscanf(line + 2, "%*s %*s %*s %*s %15s %15s", p4, p5);
        int pins[3] = { parse_pin(a3), parse_pin(p4), parse_pin(p5) };
        static const int roles[3] = { SL_SIG_CS, SL_SIG_SCK, SL_SIG_MOSI };
        const char *q = strstr(line, "chips=");
        int chips = q ? atoi(q + 6) : 1;
        for (int k = 0; k < 3; k++) {
            if (pins[k] < 0) return 1;
            sl_attach *a = &board[nboard++];
            memset(a, 0, sizeof *a);
            a->part = atoi(a1); a->kind = SL_ATT_DEVICE; a->dev = SL_DEV_MAX7219; a->addr = chips;
            a->role = roles[k]; a->pin = pins[k]; a->r_ohm = roles[k] == SL_SIG_CS ? 10000 : 0;
        }
        sl_board_set(vm, board, nboard);
        return 0;
    }
    if (!strcmp(cmd, "matrix") && n >= 2) {
        /* #!matrix N: the LEDs (# lit, . dark), then the state */
        static uint8_t px[64 * 8];
        char text[1024];
        size_t got = sl_device_read(vm, atoi(a1), px, sizeof px, text, sizeof text);
        int w = (int)got / 8;
        for (int y = 0; y < 8 && w; y++) {
            for (int x = 0; x < w; x++) putchar(px[y * w + x] ? '#' : '.');
            putchar('\n');
        }
        fputs(text, stdout);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "attach") && n >= 4 && !strcmp(a2, "tft") && nboard + 5 <= SL_MAX_ATTACH) {
        /* #!attach N tft CS SCL SDA DC RES [rows=320]: an ST7789 module (CS may be "-" for modules without one) */
        char p4[16] = "", p5[16] = "", p6[16] = "", p7[16] = "";
        sscanf(line + 2, "%*s %*s %*s %*s %15s %15s %15s %15s", p4, p5, p6, p7);
        const char *names[5] = { a3, p4, p5, p6, p7 };
        static const int roles[5] = { SL_SIG_CS, SL_SIG_SCK, SL_SIG_MOSI, SL_SIG_DC, SL_SIG_RES };
        const char *q = strstr(line, "rows=");
        int rows = q ? atoi(q + 5) : 240;
        for (int k = 0; k < 5; k++) {
            if (!strcmp(names[k], "-")) continue;
            int pin = parse_pin(names[k]);
            if (pin < 0) return 1;
            sl_attach *a = &board[nboard++];
            memset(a, 0, sizeof *a);
            a->part = atoi(a1); a->kind = SL_ATT_DEVICE; a->dev = SL_DEV_ST7789; a->addr = rows;
            a->role = roles[k]; a->pin = pin; a->r_ohm = roles[k] == SL_SIG_CS || roles[k] == SL_SIG_RES ? 10000 : 0;
        }
        sl_board_set(vm, board, nboard);
        return 0;
    }
    if (!strcmp(cmd, "tft") && n >= 2) {
        /* #!tft N: the screen in 8 x 8 blocks, each as its main colour (. black, W R G B Y C M, o for grey), then the state */
        static uint8_t px[240 * 320 * 2];
        char text[1024];
        size_t got = sl_device_read(vm, atoi(a1), px, sizeof px, text, sizeof text);
        int h = (int)(got / 480);
        for (int by = 0; by < h; by += 8) {
            for (int bx = 0; bx < 240; bx += 8) {
                int r = 0, g = 0, b = 0;
                for (int y = by; y < by + 8; y++) for (int x = bx; x < bx + 8; x++) {
                    unsigned c = px[(y * 240 + x) * 2] | px[(y * 240 + x) * 2 + 1] << 8;
                    r += (c >> 11) & 31; g += (c >> 6) & 31; b += c & 31;
                }
                r /= 64; g /= 64; b /= 64;
                int R = r > 15, G = g > 15, Bl = b > 15;
                const char *cs = ".BGCRMYW";
                char ch = cs[R << 2 | G << 1 | Bl];
                if (ch == 'W' && (r < 24 || g < 24 || b < 24)) ch = 'o';
                putchar(ch);
            }
            putchar('\n');
        }
        fputs(text, stdout);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "flash") && n >= 2) {
        /* #!flash N [ADDR [LEN]]: the state, then LEN bytes from ADDR as hex (default 32 from 0) */
        char text[1024];
        sl_device_read(vm, atoi(a1), NULL, 0, text, sizeof text);
        fputs(text, stdout);
        uint32_t at = n >= 3 ? (uint32_t)strtoul(a2, NULL, 0) : 0, len = n >= 4 ? (uint32_t)strtoul(a3, NULL, 0) : 32;
        uint8_t buf[256];
        if (len > sizeof buf) len = sizeof buf;
        size_t got = sl_device_mem(vm, atoi(a1), at, buf, len, 0);
        for (size_t k = 0; k < got; k++) printf("%s%02X", k % 16 == 0 ? (k ? "\n" : "") : " ", buf[k]);
        if (got) printf("\n");
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "poke") && n >= 4) {
        /* #!poke N ADDR HEXBYTES: write bytes into a memory part, as a programmer would */
        uint8_t buf[128]; size_t k = 0;
        const char *h = line + 2;
        for (int f = 0; f < 3 && h; f++) { h = strchr(h, ' '); while (h && *h == ' ') h++; }
        for (; h && isxdigit((unsigned char)h[0]) && isxdigit((unsigned char)h[1]) && k < sizeof buf; h += 2) {
            unsigned x; sscanf(h, "%2x", &x); buf[k++] = (uint8_t)x;
        }
        sl_device_mem(vm, atoi(a1), (uint32_t)strtoul(a2, NULL, 0), buf, k, 1);
        return 0;
    }
    if (!strcmp(cmd, "attach") && n >= 4 && !strcmp(a2, "encoder") && nboard + 5 <= SL_MAX_ATTACH) {
        /* #!attach N encoder CLK DT [SW] [bounce=US]: a KY-040: contacts A (CLK) and B (DT) to GND with
         * 10 kohm pull-ups on the module, its push switch (SW) to GND without one */
        char p4[16] = "", p5[16] = "";
        sscanf(line + 2, "%*s %*s %*s %*s %15s %15s", p4, p5);
        int clk = parse_pin(a3), dt = parse_pin(p4), sw = parse_pin(p5);
        if (clk < 0 || dt < 0) return 1;
        const char *q = strstr(line, "bounce=");
        int bounce = q ? atoi(q + 7) : 300;
        int pins[2] = { clk, dt }, roles[2] = { SL_ROLE_ENC_A, SL_ROLE_ENC_B };
        for (int k = 0; k < 2; k++) {
            sl_attach *a = &board[nboard++];
            memset(a, 0, sizeof *a);
            a->part = atoi(a1); a->kind = SL_ATT_SWITCH; a->role = roles[k]; a->pin = pins[k]; a->bounce_us = bounce;
            a = &board[nboard++];
            memset(a, 0, sizeof *a);
            a->part = atoi(a1); a->kind = SL_ATT_PULL; a->role = roles[k]; a->pin = pins[k]; a->r_ohm = 10000; a->to_vcc = 1;
        }
        if (sw >= 0) {
            sl_attach *a = &board[nboard++];
            memset(a, 0, sizeof *a);
            a->part = atoi(a1); a->kind = SL_ATT_SWITCH; a->role = 0; a->pin = sw; a->bounce_us = 2000;
        }
        sl_board_set(vm, board, nboard);
        return 0;
    }
    if (!strcmp(cmd, "turn") && n >= 3) { sl_board_turn(vm, atoi(a1), atoi(a2)); return 0; }
    if (!strcmp(cmd, "sensor") && n >= 2) {
        /* #!sensor N: a sensor's state as its sheet shows it */
        char text[1024];
        sl_device_read(vm, atoi(a1), NULL, 0, text, sizeof text);
        fputs(text, stdout);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "set") && n >= 4) {
        /* #!set N WHAT VALUE: what a sensor senses (pressure_pa, temp_c, co2_ppm, rh, breath) */
        sl_device_input(vm, atoi(a1), a2, strtod(a3, NULL));
        return 0;
    }
    if (!strcmp(cmd, "oled") && n >= 2) {
        /* #!oled N: the display as half blocks, two pixel rows per line, then its state */
        static uint8_t px[128 * 64];
        char text[1024];
        if (!sl_device_read(vm, atoi(a1), px, sizeof px, text, sizeof text)) { printf("no such part\n"); return 0; }
        static const char *const cell[4] = { " ", "\xE2\x96\x80", "\xE2\x96\x84", "\xE2\x96\x88" };   /* space, upper, lower, full */
        printf("+--------------------------------------------------------------------------------------------------------------------------------+\n");
        for (int y = 0; y < 64; y += 2) {
            putchar('|');
            for (int x = 0; x < 128; x++) fputs(cell[px[y * 128 + x] | px[(y + 1) * 128 + x] << 1], stdout);
            puts("|");
        }
        printf("+--------------------------------------------------------------------------------------------------------------------------------+\n");
        fputs(text, stdout);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(cmd, "attach") && n >= 4 && nboard < SL_MAX_ATTACH) {
        sl_attach *a = &board[nboard];
        memset(a, 0, sizeof *a);
        a->part = atoi(a1);
        a->kind = !strcmp(a2, "led") ? SL_ATT_LED : !strcmp(a2, "switch") ? SL_ATT_SWITCH : !strcmp(a2, "pull") ? SL_ATT_PULL
                : !strcmp(a2, "pot") ? SL_ATT_POT : !strcmp(a2, "audio") ? SL_ATT_AUDIO : 0;
        a->pin = parse_pin(a3);
        if (!a->kind || a->pin < 0) return 1;
        a->r_ohm = a->kind == SL_ATT_POT ? 10000 : a->kind == SL_ATT_PULL ? 10000 : 0;
        a->vf_mv = 1900;
        const char *p = line;
        for (int k = 0; k < 4 && p; k++) p = strchr(p + 1, ' ');
        while (p && *p) {
            while (*p == ' ') p++;
            if (!strncmp(p, "vcc", 3)) a->to_vcc = 1;
            else if (!strncmp(p, "r=", 2)) a->r_ohm = atoi(p + 2);
            else if (!strncmp(p, "vf=", 3)) a->vf_mv = atoi(p + 3);
            else if (!strncmp(p, "bounce=", 7)) a->bounce_us = atoi(p + 7);
            else if (!strncmp(p, "role=", 5)) a->role = atoi(p + 5);
            p = strchr(p, ' ');
        }
        nboard++;
        sl_board_set(vm, board, nboard);
        return 0;
    }
    return 1;
}

static uint8_t *read_file(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *buf = malloc((size_t)n + 1);
    if (!buf || fread(buf, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(buf); return NULL; }
    fclose(f);
    *len = (size_t)n;
    return buf;
}

int main(int argc, char **argv)
{
    sl_config cfg = { 0 };
    const char *flash_file = NULL, *script = NULL, *lines[64];
    int nlines = 0, keep_going = 0, quiet = 0, opt;
    unsigned long dump_addr = 0, dump_len = 0;
    while ((opt = getopt(argc, argv, "df:s:e:kt:qx:")) != -1) {
        switch (opt) {
        case 'd': cfg.deterministic = 1; break;
        case 'f': flash_file = optarg; break;
        case 's': script = optarg; break;
        case 'e': if (nlines < 64) lines[nlines++] = optarg; break;
        case 'k': keep_going = 1; break;
        case 't': timeout = atoi(optarg); break;
        case 'q': quiet = 1; break;
        case 'x': sscanf(optarg, "%lx,%lx", &dump_addr, &dump_len); break;
        default:
            fprintf(stderr, "usage: %s [-d] [-f flash] [-s script] [-e line]... [-k] [-t ms] [-q] firmware.bin\n", argv[0]);
            return 2;
        }
    }
    if (optind >= argc && !flash_file) {
        fprintf(stderr, "%s: no firmware given\n", argv[0]);
        return 2;
    }

    vm = sl_create(&cfg);
    size_t len = 0;
    uint8_t *image = NULL;
    if (flash_file) image = read_file(flash_file, &len);
    if (!image && optind < argc) image = read_file(argv[optind], &len);
    if (!image) { fprintf(stderr, "%s: cannot read firmware\n", argv[0]); return 1; }
    sl_load_flash(vm, image, len);
    free(image);

    pthread_t th;
    pthread_create(&th, NULL, vm_thread, NULL);

    int status = 0;
    if (script || nlines) {
        if (!sl_wait_idle(vm, timeout)) { fprintf(stderr, "firmware did not reach its prompt\n"); status = 1; }
        if (quiet) { uint8_t b[4096]; while (sl_uart_read(vm, b, sizeof b)) {} } else drain();
        FILE *f = script ? fopen(script, "r") : NULL;
        if (script && !f) { fprintf(stderr, "cannot open %s\n", script); status = 1; }
        char buf[512];
        int li = 0, lineno = 0;
        while (!status) {
            const char *line;
            if (f) {
                if (!fgets(buf, sizeof buf, f)) break;
                buf[strcspn(buf, "\r\n")] = 0;
                line = buf;
            } else {
                if (li >= nlines) break;
                line = lines[li++];
            }
            lineno++;
            if (line[0] == '#' && line[1] == '!') {
                printf("%s\n", line);
                if (directive(line)) { fprintf(stderr, "line %d: bad directive\n", lineno); status = 1; }
                sl_wait_idle(vm, timeout);
                drain();
                continue;
            }
            int r = sl_send_line(vm, line, strlen(line), timeout);
            drain();
            if (r != SL_LINE_OK) {
                fprintf(stderr, "\nline %d: %s\n", lineno,
                        r == SL_LINE_TIMEOUT ? "timeout" : r == SL_LINE_TOO_LONG ? "too long" : "not ok");
                if (!keep_going) status = 1;
            }
        }
        if (f) fclose(f);
        if (!cfg.deterministic) { usleep(100000); }
        drain();
    } else {
        pthread_t rd;
        pthread_create(&rd, NULL, reader_thread, NULL);
        char buf[512];
        while (fgets(buf, sizeof buf, stdin)) {
            size_t n = strlen(buf);
            if (n && buf[n - 1] == '\n') { buf[n - 1] = '\n'; }
            size_t off = 0;
            while (off < n) {
                size_t w = sl_uart_write(vm, (uint8_t *)buf + off, n - off);
                off += w;
                if (!w) usleep(1000);
            }
        }
        sl_wait_idle(vm, 2000);
        done = 1;
        pthread_join(rd, NULL);
        drain();
    }

    if (dump_len) {
        uint8_t *m = malloc(dump_len);
        sl_read_memory(vm, (uint32_t)dump_addr, m, dump_len);
        for (unsigned long i = 0; i < dump_len; i += 16) {
            fprintf(stderr, "%08lx:", dump_addr + i);
            for (unsigned long j = i; j < i + 16 && j < dump_len; j += 4)
                fprintf(stderr, " %08x", (unsigned)(m[j] | m[j + 1] << 8 | m[j + 2] << 16 | (unsigned)m[j + 3] << 24));
            fputc('\n', stderr);
        }
        free(m);
    }

    sl_snapshot snap;
    sl_snapshot_get(vm, &snap);
    if (snap.lockup)
        fprintf(stderr, "\ncore locked up: CFSR %08x HFSR %08x fault PC %08x\n", snap.cfsr, snap.hfsr, snap.fault_pc);

    if (flash_file) {
        uint8_t *buf = malloc(SL_FLASH_BYTES);
        sl_copy_flash(vm, buf);
        FILE *f = fopen(flash_file, "wb");
        if (f) { fwrite(buf, 1, SL_FLASH_BYTES, f); fclose(f); }
        free(buf);
    }
    sl_shutdown(vm);
    pthread_join(th, NULL);
    sl_destroy(vm);
    return status;
}
