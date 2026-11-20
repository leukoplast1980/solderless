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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static sl_vm *vm;
static volatile int done;

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
    int nlines = 0, keep_going = 0, timeout = 10000, quiet = 0, opt;
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
