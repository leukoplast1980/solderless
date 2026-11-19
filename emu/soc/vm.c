/*
 * Solderless - the VM thread, host queues and real-time pacing.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * All guest state is touched only by the thread inside sl_run(). Other
 * threads talk to it through lock-free byte rings, atomic request flags,
 * and run_on_vm() for everything else.
 */
#define _POSIX_C_SOURCE 200809L
#include "sl/solderless.h"
#include "soc.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define BATCH        200000u
#define RING_SIZE    65536u          /* power of two */
#define TAIL_SIZE    512u            /* power of two */
#define LOG_SIZE     16384u
#define AHEAD_NS     2000000ull      /* sleep when emulated time leads by this much */
#define LAG_NS       100000000ull    /* give up catching up beyond this lag */

struct ring {
    uint8_t buf[RING_SIZE];
    _Atomic uint32_t head, tail;
};

static int ring_push(struct ring *r, uint8_t b)
{
    uint32_t h = atomic_load_explicit(&r->head, memory_order_relaxed);
    uint32_t t = atomic_load_explicit(&r->tail, memory_order_acquire);
    if (h - t >= RING_SIZE) return 0;
    r->buf[h & (RING_SIZE - 1)] = b;
    atomic_store_explicit(&r->head, h + 1, memory_order_release);
    return 1;
}

static int ring_pop(struct ring *r, uint8_t *b)
{
    uint32_t t = atomic_load_explicit(&r->tail, memory_order_relaxed);
    uint32_t h = atomic_load_explicit(&r->head, memory_order_acquire);
    if (h == t) return 0;
    *b = r->buf[t & (RING_SIZE - 1)];
    atomic_store_explicit(&r->tail, t + 1, memory_order_release);
    return 1;
}

static uint32_t ring_count(struct ring *r)
{
    return atomic_load_explicit(&r->head, memory_order_acquire)
         - atomic_load_explicit(&r->tail, memory_order_acquire);
}

struct vm_request {
    void (*fn)(sl_vm *vm, void *arg);
    void *arg;
};

struct sl_vm {
    sl_config cfg;
    pthread_mutex_t lock;
    pthread_cond_t vm_cv, host_cv;

    struct sl_soc *soc;

    struct ring rx, tx;
    _Atomic int tx_new;
    uint8_t tail[TAIL_SIZE];
    uint64_t tx_total;

    /* Log (protected by log_lock) */
    pthread_mutex_t log_lock;
    char log[LOG_SIZE];
    size_t log_len;
    uint32_t log_seen[64];          /* per key: messages so far */

    /* Pacing */
    uint64_t rt_base_ns;            /* wall clock minus emulated time */
    uint64_t speed_wall0, speed_vt0;
    int speed_percent;

    /* Control */
    int running, shutdown, paused, step_req;
    int waiting;                    /* firmware waits for input */
    _Atomic int reset_req;          /* 1: NRST, 2: power cycle, 3: software reset */
    _Atomic int input_new;
    struct vm_request *request;
    uint64_t request_seq, request_done_seq;
};

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static void deadline_ns(struct timespec *ts, uint64_t abs_ns)
{
    ts->tv_sec = (time_t)(abs_ns / 1000000000ull);
    ts->tv_nsec = (long)(abs_ns % 1000000000ull);
}

static void deadline_after_ms(struct timespec *ts, int ms)
{
    deadline_ns(ts, now_ns() + (uint64_t)ms * 1000000ull);
}

static int ts_passed(const struct timespec *ts)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec > ts->tv_sec || (now.tv_sec == ts->tv_sec && now.tv_nsec >= ts->tv_nsec);
}

/* ---------------------------------------------------------------------- */
/* Hooks from the chip (VM thread)                                        */
/* ---------------------------------------------------------------------- */

static int h_rx_pop(void *host, uint8_t *b) { return ring_pop(&((sl_vm *)host)->rx, b); }
static int h_rx_ready(void *host) { return ring_count(&((sl_vm *)host)->rx) != 0; }

static void h_tx_push(void *host, uint8_t b)
{
    sl_vm *vm = host;
    ring_push(&vm->tx, b);              /* a full terminal buffer drops output */
    vm->tail[vm->tx_total & (TAIL_SIZE - 1)] = b;
    vm->tx_total++;
    atomic_store(&vm->tx_new, 1);
}

static void h_log(void *host, int key, const char *msg)
{
    sl_vm *vm = host;
    pthread_mutex_lock(&vm->log_lock);
    uint32_t *seen = &vm->log_seen[key & 63];
    if (*seen < 3) {                     /* each kind of message at most three times */
        (*seen)++;
        size_t n = strlen(msg);
        if (vm->log_len + n + 1 < LOG_SIZE) {
            memcpy(vm->log + vm->log_len, msg, n);
            vm->log_len += n;
            vm->log[vm->log_len++] = '\n';
        }
    }
    pthread_mutex_unlock(&vm->log_lock);
}

static void h_sysreset(void *host)
{
    atomic_store(&((sl_vm *)host)->reset_req, 3);
}

/* ---------------------------------------------------------------------- */
/* Creation                                                               */
/* ---------------------------------------------------------------------- */

sl_vm *sl_create(const sl_config *cfg)
{
    sl_vm *vm = calloc(1, sizeof *vm);
    if (!vm) return NULL;
    vm->soc = calloc(1, sizeof *vm->soc);
    if (!vm->soc) { free(vm); return NULL; }
    if (cfg) vm->cfg = *cfg;

    pthread_mutex_init(&vm->lock, NULL);
    pthread_mutex_init(&vm->log_lock, NULL);
    pthread_condattr_t ca;
    pthread_condattr_init(&ca);
    pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
    pthread_cond_init(&vm->vm_cv, &ca);
    pthread_cond_init(&vm->host_cv, &ca);
    pthread_condattr_destroy(&ca);

    struct sl_soc *s = vm->soc;
    s->host = vm;
    s->term_rx_pop = h_rx_pop;
    s->term_rx_ready = h_rx_ready;
    s->term_tx_push = h_tx_push;
    s->log = h_log;
    s->sysreset = h_sysreset;
    s->hse_hz = vm->cfg.hse_hz;
    s->lse_fitted = vm->cfg.lse_fitted;
    s->term_baud = vm->cfg.term_baud ? vm->cfg.term_baud : 115200u;
    soc_init(s);
    return vm;
}

void sl_destroy(sl_vm *vm)
{
    if (!vm) return;
    for (int i = 0; i < vm->soc->np; i++) { free(vm->soc->regs[i]); free(vm->soc->wmask[i]); }
    free(vm->soc);
    pthread_mutex_destroy(&vm->lock);
    pthread_mutex_destroy(&vm->log_lock);
    pthread_cond_destroy(&vm->vm_cv);
    pthread_cond_destroy(&vm->host_cv);
    free(vm);
}

/* ---------------------------------------------------------------------- */
/* Requests executed on the VM thread                                     */
/* ---------------------------------------------------------------------- */

static void run_on_vm(sl_vm *vm, void (*fn)(sl_vm *, void *), void *arg)
{
    pthread_mutex_lock(&vm->lock);
    if (!vm->running) {
        fn(vm, arg);
        pthread_mutex_unlock(&vm->lock);
        return;
    }
    while (vm->request && vm->running)
        pthread_cond_wait(&vm->host_cv, &vm->lock);
    if (!vm->running) {
        fn(vm, arg);
        pthread_mutex_unlock(&vm->lock);
        return;
    }
    struct vm_request req = { fn, arg };
    vm->request = &req;
    uint64_t seq = ++vm->request_seq;
    pthread_cond_signal(&vm->vm_cv);
    while (vm->request_done_seq < seq && vm->running)
        pthread_cond_wait(&vm->host_cv, &vm->lock);
    if (vm->request_done_seq < seq) {
        vm->request = NULL;
        fn(vm, arg);
    }
    pthread_mutex_unlock(&vm->lock);
}

static void serve_request(sl_vm *vm)
{
    if (!vm->request) return;
    vm->request->fn(vm, vm->request->arg);
    vm->request = NULL;
    vm->request_done_seq = vm->request_seq;
    pthread_cond_broadcast(&vm->host_cv);
}

/* ---------------------------------------------------------------------- */
/* Flash and debug access                                                 */
/* ---------------------------------------------------------------------- */

struct flash_io { const uint8_t *in; uint8_t *out; size_t len; int dirty; };

static void load_flash_fn(sl_vm *vm, void *arg)
{
    struct flash_io *io = arg;
    struct sl_soc *s = vm->soc;
    size_t n = io->len < SL_FLASH_SIZE ? io->len : SL_FLASH_SIZE;
    memcpy(s->flash, io->in, n);
    memset(s->flash + n, 0xFF, SL_FLASH_SIZE - n);
    memset(s->flash_dirty, 0, sizeof s->flash_dirty);
}

void sl_load_flash(sl_vm *vm, const uint8_t *image, size_t len)
{
    struct flash_io io = { .in = image, .len = len };
    run_on_vm(vm, load_flash_fn, &io);
}

static void copy_flash_fn(sl_vm *vm, void *arg)
{
    struct flash_io *io = arg;
    struct sl_soc *s = vm->soc;
    io->dirty = 0;
    for (size_t i = 0; i < sizeof s->flash_dirty; i++) io->dirty |= s->flash_dirty[i];
    if (io->out) {
        memcpy(io->out, s->flash, SL_FLASH_SIZE);
        memset(s->flash_dirty, 0, sizeof s->flash_dirty);
    }
}

int sl_copy_flash(sl_vm *vm, uint8_t *buf)
{
    struct flash_io io = { .out = buf };
    run_on_vm(vm, copy_flash_fn, &io);
    return io.dirty;
}

int sl_flash_dirty(sl_vm *vm)
{
    struct flash_io io = { 0 };
    run_on_vm(vm, copy_flash_fn, &io);
    return io.dirty;
}

struct mem_io { uint32_t addr; uint8_t *buf; size_t len; size_t done; uint32_t word; };

static void read_memory_fn(sl_vm *vm, void *arg)
{
    struct mem_io *io = arg;
    io->done = cm_peek(&vm->soc->cpu, io->addr, io->buf, io->len);
}

size_t sl_read_memory(sl_vm *vm, uint32_t addr, uint8_t *buf, size_t len)
{
    struct mem_io io = { addr, buf, len, 0, 0 };
    run_on_vm(vm, read_memory_fn, &io);
    return io.done;
}

static void peek_fn(sl_vm *vm, void *arg)
{
    struct mem_io *io = arg;
    uint8_t b[4];
    cm_peek(&vm->soc->cpu, io->addr & ~3u, b, 4);
    io->word = (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}

uint32_t sl_peek32(sl_vm *vm, uint32_t addr)
{
    struct mem_io io = { addr, NULL, 0, 0, 0 };
    run_on_vm(vm, peek_fn, &io);
    return io.word;
}

static void snapshot_fn(sl_vm *vm, void *arg)
{
    sl_snapshot *o = arg;
    struct sl_soc *s = vm->soc;
    struct cm_cpu *c = &s->cpu;
    memcpy(o->r, c->r, sizeof o->r);
    o->xpsr = cm_xpsr(c);
    o->msp = cm_msp(c);
    o->psp = cm_psp(c);
    o->primask = c->primask;
    o->basepri = c->basepri;
    o->faultmask = c->faultmask;
    o->control = c->control;
    o->cfsr = c->cfsr;
    o->hfsr = c->hfsr;
    o->mmfar = c->mmfar;
    o->bfar = c->bfar;
    o->fault_pc = c->fault_pc;
    o->cycles = c->cycles;
    o->time_ns = soc_now_ps(s) / 1000u;
    o->hclk = s->hclk;
    o->sysclk = s->sysclk;
    o->pclk1 = s->pclk1;
    o->pclk2 = s->pclk2;
    o->lockup = c->lockup;
    o->sleeping = c->sleeping;
    o->waiting_for_key = vm->waiting;
    o->paused = vm->paused;
    o->speed_percent = vm->speed_percent;
}

void sl_snapshot_get(sl_vm *vm, sl_snapshot *out)
{
    run_on_vm(vm, snapshot_fn, out);
}

size_t sl_log_read(sl_vm *vm, char *buf, size_t len)
{
    pthread_mutex_lock(&vm->log_lock);
    size_t n = 0;
    /* complete lines only */
    for (size_t i = 0; i < vm->log_len && i < len; i++)
        if (vm->log[i] == '\n') n = i + 1;
    memcpy(buf, vm->log, n);
    memmove(vm->log, vm->log + n, vm->log_len - n);
    vm->log_len -= n;
    pthread_mutex_unlock(&vm->log_lock);
    return n;
}

/* ---------------------------------------------------------------------- */
/* Control                                                                */
/* ---------------------------------------------------------------------- */

static void wake_vm(sl_vm *vm)
{
    pthread_mutex_lock(&vm->lock);
    pthread_cond_broadcast(&vm->vm_cv);
    pthread_mutex_unlock(&vm->lock);
}

void sl_shutdown(sl_vm *vm)
{
    pthread_mutex_lock(&vm->lock);
    vm->shutdown = 1;
    pthread_cond_broadcast(&vm->vm_cv);
    pthread_mutex_unlock(&vm->lock);
}

void sl_pause(sl_vm *vm, int paused)
{
    pthread_mutex_lock(&vm->lock);
    vm->paused = paused ? 1 : 0;
    pthread_cond_broadcast(&vm->vm_cv);
    pthread_mutex_unlock(&vm->lock);
}

void sl_step(sl_vm *vm)
{
    pthread_mutex_lock(&vm->lock);
    vm->step_req++;
    pthread_cond_broadcast(&vm->vm_cv);
    pthread_mutex_unlock(&vm->lock);
}

void sl_reset(sl_vm *vm) { atomic_store(&vm->reset_req, 1); wake_vm(vm); }
void sl_power_cycle(sl_vm *vm) { atomic_store(&vm->reset_req, 2); wake_vm(vm); }

/* ---------------------------------------------------------------------- */
/* Terminal                                                               */
/* ---------------------------------------------------------------------- */

size_t sl_uart_write(sl_vm *vm, const uint8_t *data, size_t len)
{
    size_t n = 0;
    while (n < len && ring_push(&vm->rx, data[n])) n++;
    if (n) {
        atomic_store(&vm->input_new, 1);
        pthread_mutex_lock(&vm->lock);
        vm->waiting = 0;
        pthread_cond_broadcast(&vm->vm_cv);
        pthread_mutex_unlock(&vm->lock);
    }
    return n;
}

size_t sl_uart_read(sl_vm *vm, uint8_t *buf, size_t len)
{
    size_t n = 0;
    while (n < len && ring_pop(&vm->tx, &buf[n])) n++;
    return n;
}

size_t sl_uart_read_wait(sl_vm *vm, uint8_t *buf, size_t len, int timeout_ms)
{
    size_t n = sl_uart_read(vm, buf, len);
    if (n || timeout_ms <= 0) return n;
    struct timespec dl;
    deadline_after_ms(&dl, timeout_ms);
    pthread_mutex_lock(&vm->lock);
    while (ring_count(&vm->tx) == 0 && !ts_passed(&dl) && !vm->shutdown)
        pthread_cond_timedwait(&vm->host_cv, &vm->lock, &dl);
    pthread_mutex_unlock(&vm->lock);
    return sl_uart_read(vm, buf, len);
}

int sl_waiting_for_key(sl_vm *vm)
{
    pthread_mutex_lock(&vm->lock);
    int w = vm->waiting && ring_count(&vm->rx) == 0;
    pthread_mutex_unlock(&vm->lock);
    return w;
}

int sl_wait_idle(sl_vm *vm, int timeout_ms)
{
    struct timespec dl;
    deadline_after_ms(&dl, timeout_ms);
    pthread_mutex_lock(&vm->lock);
    int idle;
    while (!(idle = vm->waiting && ring_count(&vm->rx) == 0) && !ts_passed(&dl) && !vm->shutdown)
        pthread_cond_timedwait(&vm->host_cv, &vm->lock, &dl);
    pthread_mutex_unlock(&vm->lock);
    return idle;
}

struct tail_io { uint64_t total; uint8_t bytes[TAIL_SIZE]; };

static void tail_fn(sl_vm *vm, void *arg)
{
    struct tail_io *io = arg;
    io->total = vm->tx_total;
    for (uint32_t i = 0; i < TAIL_SIZE; i++)
        io->bytes[i] = vm->tail[(vm->tx_total + i) & (TAIL_SIZE - 1)];
}

int sl_send_line(sl_vm *vm, const char *line, size_t len, int timeout_ms)
{
    if (len > SL_MAX_LINE) return SL_LINE_TOO_LONG;
    struct tail_io before, after;
    run_on_vm(vm, tail_fn, &before);

    struct timespec dl;
    deadline_after_ms(&dl, timeout_ms);
    size_t off = 0;
    const uint8_t nl = '\n';
    while (off < len) {
        size_t n = sl_uart_write(vm, (const uint8_t *)line + off, len - off);
        off += n;
        if (!n) {
            if (ts_passed(&dl)) return SL_LINE_TIMEOUT;
            struct timespec ts = { 0, 1000000L };
            nanosleep(&ts, NULL);
        }
    }
    while (!sl_uart_write(vm, &nl, 1)) {
        if (ts_passed(&dl)) return SL_LINE_TIMEOUT;
        struct timespec ts = { 0, 1000000L };
        nanosleep(&ts, NULL);
    }

    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long left = (long)(dl.tv_sec - now.tv_sec) * 1000 + (dl.tv_nsec - now.tv_nsec) / 1000000;
    if (left <= 0 || !sl_wait_idle(vm, (int)left)) return SL_LINE_TIMEOUT;

    run_on_vm(vm, tail_fn, &after);
    uint64_t produced = after.total - before.total;
    if (produced == 0) return SL_LINE_ERROR;
    if (produced > TAIL_SIZE) produced = TAIL_SIZE;
    const uint8_t *out = after.bytes + (TAIL_SIZE - produced);
    size_t n = (size_t)produced;
    while (n && (out[n - 1] == '\n' || out[n - 1] == '\r' || out[n - 1] == ' ')) n--;
    if (n >= 4 && !memcmp(out + n - 4, " ok.", 4)) return SL_LINE_OK;
    return SL_LINE_ERROR;
}

/* ---------------------------------------------------------------------- */
/* Main loop                                                              */
/* ---------------------------------------------------------------------- */

static void notify_output(sl_vm *vm)
{
    if (atomic_exchange(&vm->tx_new, 0)) pthread_cond_broadcast(&vm->host_cv);
}

static uint64_t vt_ns(sl_vm *vm) { return soc_now_ps(vm->soc) / 1000u; }

static void apply_requests(sl_vm *vm)
{
    int r = atomic_exchange(&vm->reset_req, 0);
    if (r) {
        struct sl_soc *s = vm->soc;
        if (r == 2) s->reset_flags = 0;
        else s->reset_flags = (1u << 26) | (r == 3 ? (1u << 28) : 0);   /* PINRSTF, SFTRSTF */
        soc_reset(s, r == 2);
        while (ring_count(&vm->rx)) { uint8_t b; ring_pop(&vm->rx, &b); }
        vm->waiting = 0;
        if (s->cpu.lockup) h_log(vm, LOG_LOCKUP, "The core is locked up: no valid vector table");
    }
    if (atomic_exchange(&vm->input_new, 0)) {
        vm->waiting = 0;
        soc_term_kick(vm->soc);
    }
}

/* Lock held. Records that the firmware waits for input. */
static void set_waiting(sl_vm *vm)
{
    struct sl_soc *s = vm->soc;
    int w = ring_count(&vm->rx) == 0 && !soc_term_busy(s) && !s->term_rx_busy;
    if (w && !vm->waiting) {
        vm->waiting = 1;
        pthread_cond_broadcast(&vm->host_cv);
    }
}

/* Lock held. The CPU is idle: sleep until the next event or input.
 * key_wait: it idles because it polls a serial port for input. */
static void idle_wait(sl_vm *vm, int key_wait)
{
    struct sl_soc *s = vm->soc;
    uint64_t next = soc_next_event_ps(s);
    if (key_wait || (s->cpu.sleeping && next == SL_NEVER)) set_waiting(vm);

    if (vm->cfg.deterministic) {
        if (next == SL_NEVER) {
            pthread_cond_wait(&vm->vm_cv, &vm->lock);
            return;
        }
        soc_skip_to(s, next);
        soc_process_events(s);
        return;
    }

    uint64_t wall = now_ns() - vm->rt_base_ns;
    uint64_t next_ns = next == SL_NEVER ? UINT64_MAX : next / 1000u;
    if (next_ns > wall) {
        uint64_t until = next_ns - wall < 100000000ull ? next_ns : wall + 100000000ull;
        struct timespec dl;
        deadline_ns(&dl, until + vm->rt_base_ns);
        pthread_cond_timedwait(&vm->vm_cv, &vm->lock, &dl);
        wall = now_ns() - vm->rt_base_ns;
    }
    uint64_t target = next_ns < wall ? next_ns : wall;
    if (target * 1000u > soc_now_ps(s)) soc_skip_to(s, target * 1000u);
    soc_process_events(s);
}

/* Lock held. Keeps emulated time from running ahead of the wall clock. */
static void pace(sl_vm *vm)
{
    uint64_t wall = now_ns() - vm->rt_base_ns, vt = vt_ns(vm);
    if (vt > wall + AHEAD_NS) {
        struct timespec dl;
        deadline_ns(&dl, vt + vm->rt_base_ns);
        pthread_cond_timedwait(&vm->vm_cv, &vm->lock, &dl);
    } else if (wall > vt + LAG_NS) {
        vm->rt_base_ns += wall - vt - LAG_NS / 4;     /* too slow: let emulated time lag */
    }
    uint64_t w = now_ns();
    if (w - vm->speed_wall0 >= 1000000000ull) {
        vm->speed_percent = (int)((vt_ns(vm) - vm->speed_vt0) * 100u / (w - vm->speed_wall0));
        vm->speed_wall0 = w;
        vm->speed_vt0 = vt_ns(vm);
    }
}

void sl_run(sl_vm *vm)
{
    struct sl_soc *s = vm->soc;
    pthread_mutex_lock(&vm->lock);
    vm->running = 1;
    vm->shutdown = 0;
    soc_reset(s, 1);
    vm->rt_base_ns = now_ns() - vt_ns(vm);
    vm->speed_wall0 = now_ns();
    vm->speed_vt0 = vt_ns(vm);
    uint64_t paused_at = 0;

    for (;;) {
        serve_request(vm);
        notify_output(vm);
        if (vm->shutdown) break;
        apply_requests(vm);

        if (vm->paused) {
            if (!paused_at) paused_at = now_ns();
            if (vm->step_req > 0) {
                vm->step_req--;
                s->cpu.sleeping = 0;
                s->idle_poll = 0;
                cm_run(&s->cpu, 1);
                soc_time_commit(s);
                soc_process_events(s);
                pthread_cond_broadcast(&vm->host_cv);
                continue;
            }
            pthread_cond_wait(&vm->vm_cv, &vm->lock);
            continue;
        }
        if (paused_at) {
            vm->rt_base_ns += now_ns() - paused_at;
            paused_at = 0;
        }

        if (s->cpu.lockup) {
            set_waiting(vm);    /* nothing will happen: let a waiting host go on */
            pthread_cond_wait(&vm->vm_cv, &vm->lock);
            continue;
        }
        if (s->cpu.sleeping) {
            if (cm_wake_pending(&s->cpu)) s->cpu.sleeping = 0;
            else { idle_wait(vm, 0); continue; }
        }
        if (s->idle_poll || s->cpu.idle_loop) {
            int key_wait = s->idle_poll && usart_index_of_periph(s, s->idle.periph) >= 0;
            s->idle_poll = 0;
            s->idle.count = 0;
            s->cpu.idle_loop = 0;
            idle_wait(vm, key_wait);
            continue;
        }

        pthread_mutex_unlock(&vm->lock);
        soc_run_batch(s, BATCH);
        pthread_mutex_lock(&vm->lock);
        if (!vm->cfg.deterministic) pace(vm);
    }

    vm->running = 0;
    if (vm->request) serve_request(vm);
    pthread_cond_broadcast(&vm->host_cv);
    pthread_mutex_unlock(&vm->lock);
}

/* ---- The breadboard ---------------------------------------------------- */

struct board_set_io { const sl_attach *att; int n; };
static void board_set_fn(sl_vm *vm, void *arg)
{
    struct board_set_io *io = arg;
    board_set(vm->soc, io->att, io->n);
}

void sl_board_set(sl_vm *vm, const sl_attach *att, int n)
{
    struct board_set_io io = { att, n };
    run_on_vm(vm, board_set_fn, &io);
    atomic_store(&vm->input_new, 1);
    wake_vm(vm);
}

struct board_input_io { int part, value; };
static void board_input_fn(sl_vm *vm, void *arg)
{
    struct board_input_io *io = arg;
    board_input(vm->soc, io->part, io->value);
}

void sl_board_input(sl_vm *vm, int part, int value)
{
    struct board_input_io io = { part, value };
    run_on_vm(vm, board_input_fn, &io);
    atomic_store(&vm->input_new, 1);
    wake_vm(vm);
}

static void board_read_fn(sl_vm *vm, void *arg) { board_read(vm->soc, arg); }

void sl_board_read(sl_vm *vm, sl_board_state *out)
{
    run_on_vm(vm, board_read_fn, out);
}

struct audio_io { sl_audio_event *out; size_t max, n; uint64_t now; };
static void audio_fn(sl_vm *vm, void *arg)
{
    struct audio_io *io = arg;
    io->n = board_audio_read(vm->soc, io->out, io->max);
    io->now = soc_now_ps(vm->soc) / 1000u;
}

size_t sl_audio_read(sl_vm *vm, sl_audio_event *out, size_t max, uint64_t *now_ns)
{
    struct audio_io io = { out, max, 0, 0 };
    run_on_vm(vm, audio_fn, &io);
    if (now_ns) *now_ns = io.now;
    return io.n;
}
