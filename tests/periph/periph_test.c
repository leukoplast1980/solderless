/*
 * Solderless - runs tests/periph/periph_fw.c on the emulated chip and acts
 * on the board when the firmware asks ('@' lines).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 *   periph_test periph_fw.bin
 */
#define _POSIX_C_SOURCE 200809L
#include "sl/solderless.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static sl_vm *vm;
static sl_attach att[8];
static int natt, harness_fails;

static void *vm_thread(void *arg) { (void)arg; sl_run(vm); return NULL; }

static int pin_of(const char *t) { return (t[1] - 'A') * 16 + atoi(t + 2); }

static void put_part(sl_attach a)
{
    for (int i = 0; i < natt; i++)
        if (att[i].part == a.part) { att[i] = a; sl_board_set(vm, att, natt); return; }
    att[natt++] = a;
    sl_board_set(vm, att, natt);
}

/* Handles one request; returns the answer byte. */
static char request(const char *req)
{
    char what[16] = "", pin[8] = "", arg[16] = "";
    long a1 = 0, a2 = 0;
    sscanf(req, "%15s %7s %15s", what, pin, arg);
    sl_board_state st;
    if (!strcmp(what, "pwm")) {
        sscanf(req, "%*s %*s %ld %ld", &a1, &a2);
        sl_board_read(vm, &st);
        int p = pin_of(pin);
        long hz = (long)(st.freq_mhz[p] / 1000u);
        int ok = (st.flags[p] & SL_PIN_PWM) && labs(hz - a1) * 200 <= a1 && labs((long)st.pwm_duty[p] - a2) <= 2;
        if (!ok) printf("  board: %s %s, %ld Hz, duty %u/1000\n", pin,
                        (st.flags[p] & SL_PIN_PWM) ? "PWM" : "no PWM", hz, st.pwm_duty[p]);
        return ok ? 'y' : 'n';
    }
    if (!strcmp(what, "floating")) {
        sl_board_read(vm, &st);
        return (st.flags[pin_of(pin)] & SL_PIN_FLOATING) ? 'y' : 'n';
    }
    if (!strcmp(what, "mv")) {
        sscanf(req, "%*s %*s %ld", &a1);
        sl_board_read(vm, &st);
        int mv = st.mv[pin_of(pin)];
        if (labs(mv - a1) > 5) printf("  board: %s at %d mV\n", pin, mv);
        return labs(mv - a1) <= 5 ? 'y' : 'n';
    }
    if (!strcmp(what, "button")) {
        sl_attach a = { 0 };
        a.part = pin_of(pin) == pin_of("PC13") ? 1 : 2;
        a.kind = SL_ATT_SWITCH;
        a.pin = pin_of(pin);
        a.to_vcc = !strcmp(arg, "vcc");
        a.bounce_us = a.to_vcc ? 0 : atoi(arg);
        put_part(a);
        return 'k';
    }
    if (!strcmp(what, "press") || !strcmp(what, "release")) {
        sl_board_input(vm, atoi(pin), what[0] == 'p');
        return 'k';
    }
    if (!strcmp(what, "pot")) {
        sl_attach a = { 0 };
        a.part = 3; a.kind = SL_ATT_POT; a.pin = pin_of(pin); a.r_ohm = 10000;
        put_part(a);
        sl_board_input(vm, 3, atoi(arg));
        return 'k';
    }
    printf("  unknown request: %s\n", req);
    harness_fails++;
    return 'n';
}

int main(int argc, char **argv)
{
    if (argc < 2) { fprintf(stderr, "usage: periph_test periph_fw.bin\n"); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 2; }
    static uint8_t image[SL_FLASH_BYTES];
    size_t n = fread(image, 1, sizeof image, f);
    fclose(f);

    sl_config cfg = { 0 };
    cfg.deterministic = 1;
    vm = sl_create(&cfg);
    sl_load_flash(vm, image, n);
    pthread_t th;
    pthread_create(&th, NULL, vm_thread, NULL);

    char line[256];
    size_t len = 0;
    int finished = 0, fw_fails = -1;
    while (!finished) {
        uint8_t buf[256];
        size_t got = sl_uart_read_wait(vm, buf, sizeof buf, 20000);
        if (!got) { printf("timeout: no output from the firmware\n"); harness_fails++; break; }
        for (size_t i = 0; i < got && !finished; i++) {
            if (buf[i] != '\n') { if (len < sizeof line - 1) line[len++] = (char)buf[i]; continue; }
            line[len] = 0;
            len = 0;
            if (line[0] == '@') {
                char answer = request(line + 1);
                sl_uart_write(vm, (const uint8_t *)&answer, 1);
            } else {
                printf("%s\n", line);
                if (!strncmp(line, "done", 4)) {
                    const char *c = strrchr(line, ' ');
                    fw_fails = c ? atoi(c + 1) : -1;
                    finished = 1;
                }
            }
        }
        char log[2048];
        size_t m;
        while ((m = sl_log_read(vm, log, sizeof log)) > 0)
            for (char *l = log; l < log + m;) {
                char *e = memchr(l, '\n', (size_t)(log + m - l));
                size_t k = e ? (size_t)(e - l) + 1 : (size_t)(log + m - l);
                printf("  [chip] %.*s", (int)k, l);
                l += k;
            }
        fflush(stdout);
    }
    sl_shutdown(vm);
    pthread_join(th, NULL);
    sl_destroy(vm);
    return (fw_fails == 0 && !harness_fails) ? 0 : 1;
}
