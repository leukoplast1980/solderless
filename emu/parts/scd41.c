/*
 * Solderless - SCD41 CO2, temperature and humidity sensor, over I2C.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * From the Sensirion SCD4x datasheet (version 1.x): 16-bit commands, data
 * in 16-bit words each followed by its CRC-8 (polynomial 0x31, start FF),
 * the command execution times (a read before they are over is not
 * acknowledged), periodic measurement every 5 s and low-power periodic
 * every 30 s, single shot (5 s) and its temperature-and-humidity-only
 * version (50 ms), the data-ready status, a measurement buffer emptied by
 * reading it (with no new data the read is not acknowledged), which
 * commands are allowed while measuring periodically, temperature offset,
 * altitude, ambient pressure, automatic self-calibration, persist, serial
 * number, self test, factory reset, reinit, power down and wake up.
 * Readings are the values the user sets, CO2 with a breath on top that
 * fades over a minute. Not yet: the effect of altitude and pressure, forced
 * recalibration that changes later readings, noise.
 */
#include "parts.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

#define PS_PER_MS 1000000000ull
#define SCD41_OFFSET_4C 1498          /* 4 degC: 4 * 65535 / 175 */

static uint8_t crc8(const uint8_t *d, int n)
{
    uint8_t c = 0xFF;
    for (int i = 0; i < n; i++) {
        c ^= d[i];
        for (int k = 0; k < 8; k++) c = (uint8_t)(c & 0x80 ? (c << 1) ^ 0x31 : c << 1);
    }
    return c;
}

static void put_word(struct scd41 *c, uint16_t w)
{
    if (c->nresp + 3 > (int)sizeof c->resp) return;
    c->resp[c->nresp++] = (uint8_t)(w >> 8);
    c->resp[c->nresp++] = (uint8_t)w;
    c->resp[c->nresp] = crc8(&c->resp[c->nresp - 2], 2);
    c->nresp++;
}

void scd41_power_on(struct scd41 *c, uint32_t seed)
{
    memset(c, 0, sizeof *c);
    c->co2 = 820; c->temp_c = 22.0; c->rh = 45;
    c->offset_raw = c->p_offset = SCD41_OFFSET_4C;   /* the factory default */
    c->asc = c->p_asc = 1;
    for (int i = 0; i < 3; i++) { seed = seed * 1103515245u + 12345u; c->serial[i] = (uint16_t)(seed >> 8); }
}

void scd41_power_cycle(struct scd41 *c)
{
    struct scd41 keep = *c;
    memset(c, 0, sizeof *c);
    c->co2 = keep.co2; c->temp_c = keep.temp_c; c->rh = keep.rh; c->breath_at = keep.breath_at;
    memcpy(c->serial, keep.serial, sizeof c->serial);
    c->p_offset = c->offset_raw = keep.p_offset;
    c->p_altitude = c->altitude = keep.p_altitude;
    c->p_asc = c->asc = keep.p_asc;
}

static double co2_now(const struct scd41 *c, uint64_t now)
{
    double v = c->co2;
    if (c->breath_at && now >= c->breath_at) {
        double t = (double)(now - c->breath_at) / 1e12;
        double peak = c->co2 > 3000 ? c->co2 : 3000;
        v += (peak - c->co2) * exp(-t / 15.0);              /* settles back over about a minute */
    }
    return v;
}

static void take(struct scd41 *c, uint64_t at, int rht_only)
{
    double co2 = co2_now(c, at);
    /* the offset makes up for the module's own warmth: 4 degC, unless set otherwise */
    double t = c->temp_c;
    if (c->offset_raw != SCD41_OFFSET_4C) t += 4.0 - c->offset_raw * 175.0 / 65535.0;
    c->out_co2 = rht_only ? 0 : (uint16_t)(co2 + 0.5);
    double traw = (t + 45.0) * 65535.0 / 175.0, hraw = c->rh * 65535.0 / 100.0;
    c->out_t = (uint16_t)(traw < 0 ? 0 : traw > 65535 ? 65535 : traw + 0.5);
    c->out_rh = (uint16_t)(hraw < 0 ? 0 : hraw > 65535 ? 65535 : hraw + 0.5);
    c->ready = 1;
    c->last_meas = at;
    c->measured++;
}

static void update(struct scd41 *c, uint64_t now)
{
    if ((c->mode == SCD41_PERIODIC || c->mode == SCD41_LOW_POWER) && now >= c->next_ps) {
        uint64_t iv = (c->mode == SCD41_PERIODIC ? 5000 : 30000) * PS_PER_MS;
        uint64_t k = (now - c->next_ps) / iv;
        uint64_t at = c->next_ps + k * iv;
        take(c, at, 0);
        c->next_ps = at + iv;
    }
    if (c->mode == SCD41_SINGLE && now >= c->next_ps) { take(c, c->next_ps, c->single_rht); c->mode = SCD41_IDLE; }
}

static void say(struct scd41 *c, int code) { if (!(c->said & (1u << code))) { c->said |= 1u << code; c->msg = code; } }

int scd41_start(struct scd41 *c, int read, uint64_t now)
{
    update(c, now);
    if (c->mode == SCD41_ASLEEP) {
        /* it does not acknowledge, but the attempt wakes it (wake_up, 36F6) */
        c->mode = SCD41_IDLE;
        c->busy_until = now + 30 * PS_PER_MS;
        return 0;
    }
    if (now < c->busy_until) { say(c, SCD41_MSG_BUSY); return 0; }
    if (!read) { c->nin = 0; c->writing = 1; return 1; }
    c->writing = 0;
    if (!c->nresp) {
        if (c->cmd == 0xEC05) say(c, SCD41_MSG_NO_DATA);
        return 0;
    }
    c->rpos = 0;
    if (c->cmd == 0xEC05) { c->ready = 0; c->reads++; }   /* reading it out empties the buffer */
    return 1;
}

int scd41_byte(struct scd41 *c, uint8_t b, uint64_t now)
{
    (void)now;
    if (c->nin < (int)sizeof c->in) c->in[c->nin++] = b;
    if (c->nin == 2) {
        uint16_t cmd = (uint16_t)(c->in[0] << 8 | c->in[1]);
        int ok_periodic = cmd == 0xEC05 || cmd == 0xE4B8 || cmd == 0x3F86 || cmd == 0xE000;
        if ((c->mode == SCD41_PERIODIC || c->mode == SCD41_LOW_POWER) && !ok_periodic) {
            c->ignored = cmd; say(c, SCD41_MSG_PERIODIC);
            c->nin = 0; c->writing = 0;
            return 0;                       /* not acknowledged */
        }
    }
    return 1;
}

static const char *cmd_name(uint16_t cmd)
{
    switch (cmd) {
    case 0x21B1: return "start_periodic_measurement";
    case 0x21AC: return "start_low_power_periodic_measurement";
    case 0xEC05: return "read_measurement";
    case 0x3F86: return "stop_periodic_measurement";
    case 0x241D: return "set_temperature_offset";
    case 0x2318: return "get_temperature_offset";
    case 0x2427: return "set_sensor_altitude";
    case 0x2322: return "get_sensor_altitude";
    case 0xE000: return "set_ambient_pressure";
    case 0x362F: return "perform_forced_recalibration";
    case 0x2416: return "set_automatic_self_calibration_enabled";
    case 0x2313: return "get_automatic_self_calibration_enabled";
    case 0xE4B8: return "get_data_ready_status";
    case 0x3615: return "persist_settings";
    case 0x3682: return "get_serial_number";
    case 0x3639: return "perform_self_test";
    case 0x3632: return "perform_factory_reset";
    case 0x3646: return "reinit";
    case 0x219D: return "measure_single_shot";
    case 0x2196: return "measure_single_shot_rht_only";
    case 0x36E0: return "power_down";
    case 0x36F6: return "wake_up";
    default: return "unknown";
    }
}

/* STOP after a write: the command runs */
void scd41_stop(struct scd41 *c, uint64_t now)
{
    if (!c->writing) { c->nresp = 0; return; }   /* after a read: the response is gone */
    c->writing = 0;
    if (c->nin < 2) return;
    uint16_t cmd = (uint16_t)(c->in[0] << 8 | c->in[1]);
    int nw = (c->nin - 2) / 3;
    uint16_t arg = 0;
    if (nw >= 1) {
        if (crc8(&c->in[2], 2) != c->in[4]) { c->ignored = cmd; say(c, SCD41_MSG_CRC); return; }
        arg = (uint16_t)(c->in[2] << 8 | c->in[3]);
    }
    c->cmd = cmd;
    c->cmd_at = now;
    c->commands++;
    c->nresp = 0;
    uint64_t exec = 1;
    switch (cmd) {
    case 0x21B1: c->mode = SCD41_PERIODIC; c->next_ps = now + 5000 * PS_PER_MS; c->ready = 0; break;
    case 0x21AC: c->mode = SCD41_LOW_POWER; c->next_ps = now + 30000 * PS_PER_MS; c->ready = 0; break;
    case 0x3F86: c->mode = SCD41_IDLE; exec = 500; break;
    case 0xEC05:
        if (c->ready) { put_word(c, c->out_co2); put_word(c, c->out_t); put_word(c, c->out_rh); }
        break;
    case 0xE4B8: put_word(c, c->ready ? 0x8006 : 0x8000); break;
    case 0x241D: if (nw) { c->offset_raw = arg; c->changed = 1; } break;
    case 0x2318: put_word(c, c->offset_raw); break;
    case 0x2427: if (nw) { c->altitude = arg; c->changed = 1; } break;
    case 0x2322: put_word(c, c->altitude); break;
    case 0xE000: if (nw) c->pressure_hpa = arg; break;
    case 0x2416: if (nw) { c->asc = arg != 0; c->changed = 1; } break;
    case 0x2313: put_word(c, (uint16_t)c->asc); break;
    case 0x362F: exec = 400; put_word(c, nw ? (uint16_t)(arg - (uint16_t)(co2_now(c, now) + 0.5) + 0x8000) : 0xFFFF); break;
    case 0x3615: c->changed = 0; c->p_offset = c->offset_raw; c->p_altitude = c->altitude; c->p_asc = c->asc; exec = 800; break;
    case 0x3682: for (int i = 0; i < 3; i++) put_word(c, c->serial[i]); break;
    case 0x3639: exec = 10000; put_word(c, 0); break;
    case 0x3632:
        exec = 1200; c->offset_raw = c->p_offset = SCD41_OFFSET_4C; c->altitude = c->p_altitude = 0; c->asc = c->p_asc = 1;
        c->changed = 0;
        break;
    case 0x3646: exec = 30; break;
    case 0x219D: c->mode = SCD41_SINGLE; c->single_rht = 0; c->next_ps = now + 5000 * PS_PER_MS; c->ready = 0; break;
    case 0x2196: c->mode = SCD41_SINGLE; c->single_rht = 1; c->next_ps = now + 50 * PS_PER_MS; c->ready = 0; break;
    case 0x36E0: c->mode = SCD41_ASLEEP; break;
    default: c->ignored = cmd; say(c, SCD41_MSG_UNKNOWN); break;
    }
    c->busy_until = now + exec * PS_PER_MS;
}

uint8_t scd41_read(struct scd41 *c)
{
    return c->rpos < c->nresp ? c->resp[c->rpos++] : 0xFF;
}

void scd41_input(struct scd41 *c, const char *what, double v, uint64_t now)
{
    update(c, now);
    if (!strcmp(what, "co2_ppm")) c->co2 = v < 400 ? 400 : v > 5000 ? 5000 : v;
    else if (!strcmp(what, "temp_c")) c->temp_c = v < -10 ? -10 : v > 60 ? 60 : v;
    else if (!strcmp(what, "rh")) c->rh = v < 0 ? 0 : v > 100 ? 100 : v;
    else if (!strcmp(what, "breath")) c->breath_at = now;
}

int scd41_message(struct scd41 *c, char *out, size_t len)
{
    int m = c->msg;
    c->msg = 0;
    switch (m) {
    case SCD41_MSG_PERIODIC:
        snprintf(out, len, "SCD41: command %04X (%s) not acknowledged: while it measures periodically it takes only "
                 "read_measurement, get_data_ready_status, set_ambient_pressure and stop_periodic_measurement (3F86, then "
                 "500 ms)", c->ignored, cmd_name(c->ignored));
        return m;
    case SCD41_MSG_NO_DATA:
        snprintf(out, len, "SCD41: read_measurement not acknowledged: no new measurement since the last read (one every "
                 "5 s, the first 5 s after the start). get_data_ready_status (E4B8) tells when there is one");
        return m;
    case SCD41_MSG_BUSY:
        snprintf(out, len, "SCD41: it does not answer while a command runs: %s (%04X) takes its time before the next "
                 "transfer (read_measurement 1 ms, stop_periodic_measurement 500 ms)", cmd_name(c->cmd), c->cmd);
        return m;
    case SCD41_MSG_CRC:
        snprintf(out, len, "SCD41: command %04X ignored: the CRC after its data word is wrong (CRC-8, polynomial 31, "
                 "starting at FF)", c->ignored);
        return m;
    case SCD41_MSG_UNKNOWN:
        snprintf(out, len, "SCD41: %04X is not a command of the SCD4x", c->ignored);
        return m;
    default:
        return 0;
    }
}

size_t scd41_text(struct scd41 *c, uint64_t now, char *out, size_t len)
{
    update(c, now);
    size_t n = 0;
#define ADD(...) do { if (n < len) { int k_ = snprintf(out + n, len - n, __VA_ARGS__); if (k_ > 0) n += (size_t)k_; } } while (0)
    ADD("Reading\t%.0f ppm · %.1f °C · %.0f %%\n", co2_now(c, now), c->temp_c, c->rh);
    switch (c->mode) {
    case SCD41_PERIODIC: ADD("mode\tperiodic, every 5 s\n"); break;
    case SCD41_LOW_POWER: ADD("mode\tlow power periodic, every 30 s\n"); break;
    case SCD41_SINGLE: ADD("mode\tsingle shot%s\n", c->single_rht ? ", temperature and humidity only" : ""); break;
    case SCD41_ASLEEP: ADD("mode\tpowered down\n"); break;
    default: ADD("mode\tidle\n"); break;
    }
    if (c->ready) ADD("data\tready to read\n");
    else if (c->mode == SCD41_PERIODIC || c->mode == SCD41_LOW_POWER || c->mode == SCD41_SINGLE)
        ADD("data\tready in %.1f s\n", (double)(c->next_ps - now) / 1e12);
    else ADD("data\tnone\n");
    ADD("serial\t%04X %04X %04X\n", c->serial[0], c->serial[1], c->serial[2]);
    ADD("settings\toffset %.1f °C · altitude %u m · ASC %s%s\n", c->offset_raw * 175.0 / 65535.0, c->altitude,
        c->asc ? "on" : "off", c->changed ? " · changed, not persisted" : "");
    if (c->commands) ADD("last command\t%04X %s · %.1f s ago\n", c->cmd, cmd_name(c->cmd), (double)(now - c->cmd_at) / 1e12);
#undef ADD
    return n;
}
