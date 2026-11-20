/*
 * Solderless - ST7789 colour TFT controller on the common IPS modules,
 * 240 x 240 (1.3" and 1.54") or 240 x 320 (2.0"), 4-wire SPI with DC.
 * Sitronix ST7789VW datasheet, v1.0.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Modelled: the 240 x 320 frame memory, of which a 240-row panel shows
 * rows 0-239 (so a rotation that mirrors rows needs an 80-row offset, as on
 * the real module); hardware reset (RES low), software reset, sleep in and
 * out with the 5 ms wait the datasheet asks for before the next command
 * (commands sent earlier are ignored here), display on and off, inversion
 * (these IPS panels show true colours with inversion on), COLMOD with
 * 12-, 16- and 18-bit pixels (18 after reset), MADCTL (MY, MX, MV, RGB/BGR),
 * the column and row window, memory write and write continue, idle mode.
 * Other commands (porch, gamma, power) are accepted with their parameters.
 * Not yet: reads, partial mode, vertical scrolling, tearing effect.
 */
#include "parts.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PS_PER_MS 1000000000ull

static void reset_regs(struct st7789 *t)
{
    t->sleeping = 1; t->on = 0; t->inverted = 0; t->idle = 0;
    t->colmod = 0x66; t->madctl = 0;
    t->xs = 0; t->xe = 239; t->ys = 0; t->ye = 319;
    t->x = 0; t->y = 0;
    t->writing = 0; t->pxn = 0;
    t->cmd = 0; t->nargs = 0;
}

int st7789_init(struct st7789 *t, int rows, uint32_t seed)
{
    memset(t, 0, sizeof *t);
    t->panel_h = rows == 320 ? 320 : 240;
    t->ram = malloc(ST7789_W * ST7789_H * sizeof *t->ram);
    if (!t->ram) return -1;
    for (int i = 0; i < ST7789_W * ST7789_H; i++) { seed = seed * 1103515245u + 12345u; t->ram[i] = (uint16_t)(seed >> 12); }
    reset_regs(t);
    return 0;
}

void st7789_free(struct st7789 *t) { free(t->ram); t->ram = 0; }

static void say(struct st7789 *t, int code) { if (!(t->said & (1u << code))) { t->said |= 1u << code; t->msg = code; } }

/* RES: low resets the chip; it takes 120 ms (tRT) before it is ready again */
void st7789_reset_pin(struct st7789 *t, int low, uint64_t now)
{
    if (low) { t->in_reset = 1; return; }
    if (!t->in_reset) return;
    t->in_reset = 0;
    reset_regs(t);
    t->ready_at = now + 120 * PS_PER_MS;
    t->wait_why = "a hardware reset (RES)";
    t->wait_ms = 120;
}

/* A pixel at the logical position, through MADCTL into the frame memory */
static void put(struct st7789 *t, uint16_t c)
{
    int col = t->x, row = t->y;
    if (t->madctl & 0x20) { col = t->y; row = t->x; }     /* MV: rows and columns swap */
    if (t->madctl & 0x40) col = ST7789_W - 1 - col;       /* MX */
    if (t->madctl & 0x80) row = ST7789_H - 1 - row;       /* MY */
    if (col >= 0 && col < ST7789_W && row >= 0 && row < ST7789_H) {
        t->ram[row * ST7789_W + col] = c;
        if (row >= t->panel_h) t->offscreen++;
    }
    t->pixels++;
    /* next: along the row, then down; back to the top of the window at its end */
    if (++t->x > t->xe) { t->x = t->xs; if (++t->y > t->ye) t->y = t->ys; }
}

static void pixel_byte(struct st7789 *t, uint8_t b, uint64_t now)
{
    if (t->sleeping) say(t, ST7789_MSG_ASLEEP);
    else if (!t->on) say(t, ST7789_MSG_OFF);
    if ((t->colmod & 7) == 6 && !t->colmod_set) say(t, ST7789_MSG_COLMOD);
    t->last_ps = now;
    t->px[t->pxn++] = b;
    switch (t->colmod & 7) {
    case 5:                                               /* 16 bits: RRRRRGGG GGGBBBBB */
        if (t->pxn == 2) { put(t, (uint16_t)(t->px[0] << 8 | t->px[1])); t->pxn = 0; }
        break;
    case 3:                                               /* 12 bits: two pixels in three bytes */
        if (t->pxn == 3) {
            uint16_t a = (uint16_t)(t->px[0] << 4 | t->px[1] >> 4), c = (uint16_t)((t->px[1] & 15) << 8 | t->px[2]);
            for (int k = 0; k < 2; k++) {
                uint16_t v = k ? c : a;
                int r = (v >> 8) & 15, g = (v >> 4) & 15, bl = v & 15;
                int r5 = r << 1 | r >> 3, g6 = g << 2 | g >> 2, b5 = bl << 1 | bl >> 3;
                put(t, (uint16_t)(r5 << 11 | g6 << 5 | b5));
            }
            t->pxn = 0;
        }
        break;
    default:                                              /* 18 bits: R, G, B in the top six bits of a byte each */
        if (t->pxn == 3) { put(t, (uint16_t)((t->px[0] & 0xF8) << 8 | (t->px[1] & 0xFC) << 3 | t->px[2] >> 3)); t->pxn = 0; }
        break;
    }
}

static void command(struct st7789 *t, uint8_t c, uint64_t now)
{
    t->writing = 0; t->pxn = 0;
    if (now < t->ready_at && !t->in_reset) {
        t->ignored = c;
        t->early_ms = (double)(now + t->wait_ms * PS_PER_MS - t->ready_at) / 1e9;
        say(t, ST7789_MSG_EARLY);
        t->cmd = 0xFF;                                    /* its parameters go nowhere */
        return;
    }
    t->cmd = c; t->nargs = 0;
    t->commands++;
    switch (c) {
    case 0x01: reset_regs(t); t->ready_at = now + 5 * PS_PER_MS; t->wait_why = "a software reset (01)"; t->wait_ms = 5; break;
    case 0x10: t->sleeping = 1; t->ready_at = now + 5 * PS_PER_MS; t->wait_why = "Sleep In (10)"; t->wait_ms = 5; break;
    case 0x11: t->sleeping = 0; t->ready_at = now + 5 * PS_PER_MS; t->wait_why = "Sleep Out (11)"; t->wait_ms = 5; break;
    case 0x20: t->inverted = 0; break;
    case 0x21: t->inverted = 1; break;
    case 0x28: t->on = 0; break;
    case 0x29: t->on = 1; break;
    case 0x38: t->idle = 0; break;
    case 0x39: t->idle = 1; break;
    case 0x2C: t->x = t->xs; t->y = t->ys; t->writing = 1; t->run = 0; t->run_x = t->xs; t->run_y = t->ys; break;
    case 0x3C: t->writing = 1; t->run = 0; t->run_x = t->x; t->run_y = t->y; break;
    default: break;
    }
}

static void param(struct st7789 *t, uint8_t b)
{
    if (t->nargs < (int)sizeof t->args) t->args[t->nargs] = b;
    t->nargs++;
    switch (t->cmd) {
    case 0x2A:
        if (t->nargs == 2) t->xs = t->args[0] << 8 | t->args[1];
        if (t->nargs == 4) { t->xe = t->args[2] << 8 | t->args[3]; t->x = t->xs; }
        break;
    case 0x2B:
        if (t->nargs == 2) t->ys = t->args[0] << 8 | t->args[1];
        if (t->nargs == 4) { t->ye = t->args[2] << 8 | t->args[3]; t->y = t->ys; }
        break;
    case 0x36: if (t->nargs == 1) t->madctl = b; break;
    case 0x3A: if (t->nargs == 1) { t->colmod = b; t->colmod_set = 1; } break;
    default: break;
    }
}

void st7789_byte(struct st7789 *t, uint8_t b, int dc, uint64_t now)
{
    if (t->in_reset) return;
    if (!dc) { command(t, b, now); return; }
    if (t->cmd == 0xFF) return;
    if (t->writing) { int before = t->pixels; pixel_byte(t, b, now); t->run += t->pixels - before; }
    else param(t, b);
}

int st7789_message(struct st7789 *t, char *out, size_t len)
{
    int m = t->msg;
    t->msg = 0;
    switch (m) {
    case ST7789_MSG_EARLY:
        snprintf(out, len, "ST7789: command %02X ignored, sent %.1f ms after %s; the datasheet asks for %d ms before "
                 "the next command", t->ignored, t->early_ms, t->wait_why, t->wait_ms);
        return m;
    case ST7789_MSG_ASLEEP:
        snprintf(out, len, "ST7789: pixels go into its memory, but it is still asleep: send Sleep Out (11), wait 5 ms, "
                 "then Display On (29)");
        return m;
    case ST7789_MSG_OFF:
        snprintf(out, len, "ST7789: pixels go into its memory, but the display is off: send Display On (29)");
        return m;
    case ST7789_MSG_COLMOD:
        snprintf(out, len, "ST7789: pixels in 18-bit colour, 3 bytes each (COLMOD 66, its value after reset). For "
                 "RGB565, 2 bytes a pixel, send 3A 55 first");
        return m;
    case ST7789_MSG_OFFSCREEN:
        snprintf(out, len, "ST7789: pixels went to memory rows 240-319, which this 240-row panel does not show. With "
                 "MADCTL mirroring rows (MY), add 80 to the rows in RASET");
        return m;
    default:
        return 0;
    }
}

void st7789_check(struct st7789 *t)
{
    if (t->offscreen && t->panel_h < ST7789_H) say(t, ST7789_MSG_OFFSCREEN);
}

/* The panel as lit: RGB565, row by row, 240 wide, panel_h high */
void st7789_pixels(const struct st7789 *t, uint16_t *px)
{
    int n = ST7789_W * t->panel_h;
    if (t->in_reset || t->sleeping || !t->on) { memset(px, 0, (size_t)n * 2); return; }
    for (int i = 0; i < n; i++) {
        uint16_t c = t->ram[i];
        if (t->madctl & 0x08) c = (uint16_t)((c & 0x07E0) | (c >> 11) | (c << 11));   /* BGR order */
        if (!t->inverted) c = (uint16_t)~c;               /* IPS panel: true colours only with INVON */
        if (t->idle) c &= 0x8410;                          /* idle mode: 8 colours */
        px[i] = c;
    }
}

size_t st7789_text(const struct st7789 *t, uint64_t now, char *out, size_t len)
{
    size_t n = 0;
#define ADD(...) do { if (n < len) { int k_ = snprintf(out + n, len - n, __VA_ARGS__); if (k_ > 0) n += (size_t)k_; } } while (0)
    ADD("Panel\tST7789 · 240 × %d IPS\n", t->panel_h);
    if (t->in_reset) ADD("Reset\tRES is low: held in reset\n");
    ADD("Sleep\t%s\n", t->sleeping ? "asleep (Sleep Out is 11)" : "awake");
    ADD("Display\t%s\n", t->on ? "on" : "off (Display On is 29)");
    const char *cm = (t->colmod & 7) == 5 ? "16-bit RGB565" : (t->colmod & 7) == 3 ? "12-bit" : "18-bit, 3 bytes a pixel";
    ADD("Colour\t%s (3A %02X%s)\n", cm, t->colmod, t->colmod_set ? "" : ", after reset");
    ADD("Inversion\t%s\n", t->inverted ? "on (21): true colours" : "off: this IPS panel shows colours inverted");
    ADD("Orientation\tMADCTL %02X · MY %d MX %d MV %d · %s\n", t->madctl, (t->madctl >> 7) & 1, (t->madctl >> 6) & 1,
        (t->madctl >> 5) & 1, (t->madctl & 0x08) ? "BGR" : "RGB");
    ADD("Window\tcolumns %d–%d · rows %d–%d\n", t->xs, t->xe, t->ys, t->ye);
    if (t->pixels) ADD("Last write\t%d pixels from (%d, %d) · %.0f ms ago\n", t->run, t->run_x, t->run_y,
                       (double)(now - t->last_ps) / 1e9);
#undef ADD
    return n;
}
