/*
 * Solderless - BMP280 pressure and temperature sensor, over I2C.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * From the Bosch BMP280 datasheet, rev. 1.26: the register map (section 4),
 * sleep, forced and normal mode with the standby times, the oversampling
 * settings with their resolution (16 to 20 bits) and measurement times
 * (typical values of 3.8.1), the IIR filter, the soft reset, and readings
 * made from the values the user sets through the datasheet's own
 * compensation formulas (8.2) run backwards, with the calibration words of
 * the datasheet's example chip. Data registers are shadowed: a burst read
 * during a measurement gets the previous one. Every register reads as on
 * the chip; registers that do not exist read FF.
 * Not yet: SPI (the module's CSB is tied high), noise.
 */
#include "parts.h"

#include <stdio.h>
#include <string.h>

#define PS_PER_US 1000000ull

/* The datasheet's example calibration (table 17 and 8.2) */
static const uint16_t T1 = 27504;
static const int16_t T2 = 26435, T3 = -1000;
static const uint16_t P1 = 36477;
static const int16_t P2 = -10685, P3 = 3024, P4 = 2855, P5 = 140, P6 = -7, P7 = 15500, P8 = -14600, P9 = 6000;

static int32_t comp_t(int32_t adc, int32_t *t_fine)
{
    int32_t v1 = ((((adc >> 3) - ((int32_t)T1 << 1))) * (int32_t)T2) >> 11;
    int32_t v2 = (((((adc >> 4) - (int32_t)T1) * ((adc >> 4) - (int32_t)T1)) >> 12) * (int32_t)T3) >> 14;
    *t_fine = v1 + v2;
    return (*t_fine * 5 + 128) >> 8;                /* 0.01 degC */
}

static uint32_t comp_p(int32_t adc, int32_t t_fine)
{
    /* as in the datasheet: right shifts are arithmetic, left shifts written as products */
    int64_t v1 = (int64_t)t_fine - 128000;
    int64_t v2 = v1 * v1 * (int64_t)P6;
    v2 = v2 + v1 * (int64_t)P5 * 131072;
    v2 = v2 + (int64_t)P4 * 34359738368;
    v1 = ((v1 * v1 * (int64_t)P3) >> 8) + v1 * (int64_t)P2 * 4096;
    v1 = ((((int64_t)1) << 47) + v1) * (int64_t)P1 >> 33;
    if (v1 == 0) return 0;
    int64_t p = 1048576 - adc;
    p = ((p * 2147483648) - v2) * 3125 / v1;
    v1 = ((int64_t)P9 * (p >> 13) * (p >> 13)) >> 25;
    v2 = ((int64_t)P8 * p) >> 19;
    p = ((p + v1 + v2) >> 8) + (int64_t)P7 * 16;
    return (uint32_t)p;                             /* Pa in Q24.8 */
}

/* The raw readings that compensate to the values set */
static void raw_for(const struct bmp280 *b, int32_t *at, int32_t *ap)
{
    int32_t lo = 0, hi = (1 << 20) - 1, tf;
    int32_t want = (int32_t)(b->temp_c * 100.0 + (b->temp_c >= 0 ? 0.5 : -0.5));
    while (lo < hi) {                               /* T rises with adc_T */
        int32_t mid = (lo + hi) / 2;
        if (comp_t(mid, &tf) < want) lo = mid + 1; else hi = mid;
    }
    *at = lo;
    comp_t(lo, &tf);
    uint32_t wantp = (uint32_t)(b->pressure_pa * 256.0);
    lo = 0; hi = (1 << 20) - 1;
    while (lo < hi) {                               /* p falls as adc_P rises */
        int32_t mid = (lo + hi) / 2;
        if (comp_p(mid, tf) > wantp) lo = mid + 1; else hi = mid;
    }
    *ap = lo;
}

static void reset_regs(struct bmp280 *b)
{
    b->ctrl_meas = 0; b->config = 0;
    b->adc_t = b->adc_p = 0x80000;
    b->filt_t = b->filt_p = -1;
    b->busy_until = 0; b->next_ps = 0; b->ran = 0;
}

void bmp280_power_on(struct bmp280 *b)
{
    memset(b, 0, sizeof *b);
    b->pressure_pa = 101325.0;
    b->temp_c = 21.5;
    reset_regs(b);
}

static int samples(int osrs) { return osrs == 0 ? 0 : osrs >= 5 ? 16 : 1 << (osrs - 1); }

/* Typical measurement time (datasheet 3.8.1), in microseconds */
static uint64_t t_meas_us(const struct bmp280 *b)
{
    int t = samples(b->ctrl_meas >> 5), p = samples((b->ctrl_meas >> 2) & 7);
    return 1000u + 2000u * (uint64_t)t + (p ? 2000u * (uint64_t)p + 500u : 0);
}

static uint64_t t_standby_us(const struct bmp280 *b)
{
    static const uint32_t sb[8] = { 500, 62500, 125000, 250000, 500000, 1000000, 2000000, 4000000 };
    return sb[b->config >> 5];
}

/* One measurement done: new readings through oversampling and the filter */
static void measure(struct bmp280 *b)
{
    int32_t at, ap;
    raw_for(b, &at, &ap);
    int ot = b->ctrl_meas >> 5, op = (b->ctrl_meas >> 2) & 7;
    int fc = (b->config >> 2) & 7;
    int coef = fc == 0 ? 1 : fc >= 4 ? 16 : 1 << fc;
    /* resolution: 16 bits at x1, one more bit per doubling, up to 20 */
    if (ot) {
        int bits = 15 + (ot > 5 ? 5 : ot);
        int32_t t = at & ~((1 << (20 - bits)) - 1);
        b->filt_t = b->filt_t < 0 || coef == 1 ? t : (b->filt_t * (coef - 1) + t) / coef;
        b->adc_t = b->filt_t;
    } else b->adc_t = 0x80000;
    if (op && ot) {
        int bits = 15 + (op > 5 ? 5 : op);
        int32_t p = ap & ~((1 << (20 - bits)) - 1);
        b->filt_p = b->filt_p < 0 || coef == 1 ? p : (b->filt_p * (coef - 1) + p) / coef;
        b->adc_p = b->filt_p;
    } else b->adc_p = 0x80000;
    b->ran++;
}

/* Brings the measurements up to now */
static void update(struct bmp280 *b, uint64_t now)
{
    int mode = b->ctrl_meas & 3;
    if (b->busy_until && now >= b->busy_until) {
        measure(b);
        b->last_done = b->busy_until;
        b->busy_until = 0;
        if (mode == 1 || mode == 2) b->ctrl_meas &= ~3u;     /* forced: back to sleep */
    }
    if ((b->ctrl_meas & 3) == 3) {
        uint64_t period = (t_meas_us(b) + t_standby_us(b)) * PS_PER_US;
        /* long unread: the filter has settled long ago, so only the last 64 measurements matter */
        if (b->next_ps && now > b->next_ps + 64 * period) b->next_ps += ((now - b->next_ps) / period - 64) * period;
        while (b->next_ps && now >= b->next_ps + t_meas_us(b) * PS_PER_US) {
            measure(b);
            b->last_done = b->next_ps + t_meas_us(b) * PS_PER_US;
            b->next_ps += period;
        }
    }
}

static int measuring(const struct bmp280 *b, uint64_t now)
{
    if (b->busy_until && now < b->busy_until) return 1;
    if ((b->ctrl_meas & 3) == 3 && b->next_ps && now >= b->next_ps) return now < b->next_ps + t_meas_us(b) * PS_PER_US;
    return 0;
}

void bmp280_start(struct bmp280 *b, int read, uint64_t now)
{
    update(b, now);
    b->reading = read;
    b->first = !read;
    /* a burst read sees one consistent set of readings */
    b->shadow_t = b->adc_t; b->shadow_p = b->adc_p;
}

static void write_reg(struct bmp280 *b, uint8_t reg, uint8_t v, uint64_t now)
{
    switch (reg) {
    case 0xE0:
        if (v == 0xB6) { reset_regs(b); b->did_reset = 1; }
        break;
    case 0xF4: {
        b->ctrl_meas = v;
        b->wrote_ctrl = 1;
        int mode = v & 3;
        if (mode == 1 || mode == 2) b->busy_until = now + t_meas_us(b) * PS_PER_US;
        else if (mode == 3) b->next_ps = now;
        else b->busy_until = 0;
        break;
    }
    case 0xF5:
        b->config = v & 0xFD;
        b->wrote_config = 1;
        break;
    default:
        break;
    }
}

void bmp280_byte(struct bmp280 *b, uint8_t v, uint64_t now)
{
    /* writes come as pairs: the register, then its value */
    if (b->first) { b->ptr = v; b->first = 0; b->pair = 1; return; }
    if (b->pair) { write_reg(b, b->ptr, v, now); b->pair = 0; return; }
    b->ptr = v; b->pair = 1;
}

uint8_t bmp280_read(struct bmp280 *b, uint64_t now)
{
    uint8_t r = b->ptr, v = 0xFF;
    if (r >= 0x88 && r <= 0x9F) {
        static const int16_t cal[12] = { (int16_t)27504, 26435, -1000, (int16_t)36477, -10685, 3024, 2855, 140, -7,
                                         15500, -14600, 6000 };
        uint16_t w = (uint16_t)cal[(r - 0x88) / 2];
        v = (uint8_t)((r & 1) ? w >> 8 : w);
        b->cal_read |= 1u << (r - 0x88);
    } else switch (r) {
    case 0xD0: v = 0x58; b->id_read = 1; break;
    case 0xE0: v = 0x00; break;
    case 0xF3: v = (uint8_t)(measuring(b, now) ? 0x08 : 0x00); break;
    case 0xF4: v = b->ctrl_meas; break;
    case 0xF5: v = b->config; break;
    case 0xF7: v = (uint8_t)(b->shadow_p >> 12); b->data_read = 1; break;
    case 0xF8: v = (uint8_t)(b->shadow_p >> 4); break;
    case 0xF9: v = (uint8_t)(b->shadow_p << 4); break;
    case 0xFA: v = (uint8_t)(b->shadow_t >> 12); b->data_read = 1; break;
    case 0xFB: v = (uint8_t)(b->shadow_t >> 4); break;
    case 0xFC: v = (uint8_t)(b->shadow_t << 4); break;
    default: break;
    }
    if ((r == 0xF7 || r == 0xFA) && b->ran == 0 && !(b->said & 1)) { b->said |= 1; b->msg = BMP280_MSG_NO_MEASUREMENT; }
    else if ((r == 0xF7 || r == 0xFA) && b->cal_read != 0xFFFFFF && !(b->said & 2)) { b->said |= 2; b->msg = BMP280_MSG_NO_CALIBRATION; }
    b->ptr = (uint8_t)(r + 1);                     /* the pointer moves on */
    return v;
}

void bmp280_input(struct bmp280 *b, const char *what, double v, uint64_t now)
{
    update(b, now);
    if (!strcmp(what, "pressure_pa")) b->pressure_pa = v < 30000 ? 30000 : v > 110000 ? 110000 : v;
    else if (!strcmp(what, "temp_c")) b->temp_c = v < -40 ? -40 : v > 85 ? 85 : v;
}

int bmp280_message(struct bmp280 *b, char *out, size_t len)
{
    int m = b->msg;
    b->msg = 0;
    if (m == BMP280_MSG_NO_MEASUREMENT)
        snprintf(out, len, "BMP280: no measurement has run, so the data registers hold 80000, their reset value. "
                 "Write ctrl_meas (F4): mode 01 for one measurement, 11 for one every standby time");
    else if (m == BMP280_MSG_NO_CALIBRATION)
        snprintf(out, len, "BMP280: the raw readings need this chip's 24 calibration bytes (88-9F) to become degrees "
                 "and pascals; every chip has its own");
    else return 0;
    return m;
}

static const char *x_of(int osrs)
{
    static const char *const x[8] = { "skipped", "×1", "×2", "×4", "×8", "×16", "×16", "×16" };
    return x[osrs & 7];
}

size_t bmp280_text(struct bmp280 *b, uint64_t now, char *out, size_t len)
{
    update(b, now);
    size_t n = 0;
#define ADD(...) do { if (n < len) { int k_ = snprintf(out + n, len - n, __VA_ARGS__); if (k_ > 0) n += (size_t)k_; } } while (0)
    ADD("Reading\t%.1f hPa · %.1f °C\n", b->pressure_pa / 100.0, b->temp_c);
    ADD("chip id\t%s\n", b->id_read ? "0x58 read ✓" : "not read yet");
    int cal = 0;
    for (int k = 0; k < 24; k++) cal += (b->cal_read >> k) & 1;
    ADD("calibration\t%s\n", cal == 24 ? "24 bytes read ✓" : cal ? "only part read" : "not read yet");
    static const char *const modes[4] = { "sleep", "forced", "forced", "normal" };
    if (b->wrote_ctrl)
        ADD("ctrl_meas\t0x%02X  %s, T %s, P %s\n", b->ctrl_meas, modes[b->ctrl_meas & 3], x_of(b->ctrl_meas >> 5),
            x_of((b->ctrl_meas >> 2) & 7));
    else ADD("ctrl_meas\t0x00  sleep (after power-up)\n");
    static const char *const sb[8] = { "0.5 ms", "62.5 ms", "125 ms", "250 ms", "500 ms", "1 s", "2 s", "4 s" };
    int fc = (b->config >> 2) & 7;
    ADD("config\t0x%02X  standby %s, filter %s\n", b->config, sb[b->config >> 5],
        fc == 0 ? "off" : fc == 1 ? "×2" : fc == 2 ? "×4" : fc == 3 ? "×8" : "×16");
    if (b->ran)
        ADD("last measurement\t%.1f ms ago, takes %.1f ms\n", (double)(now - b->last_done) / 1e9, (double)t_meas_us(b) / 1000.0);
    else ADD("last measurement\tnone yet\n");
#undef ADD
    return n;
}
