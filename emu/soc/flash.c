/*
 * Solderless - embedded flash memory and its controller (RM0440 chapter 5).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Programming works on 64-bit double words written as two consecutive word
 * stores while FLASH_CR.PG is set. Erase works on 2 KiB pages. Both take
 * the datasheet's time, during which FLASH_SR.BSY is set.
 */
#include "soc.h"

#include <stdio.h>
#include <string.h>

#define ACR     0x00u
#define KEYR    0x08u
#define OPTKEYR 0x0Cu
#define SR      0x10u
#define CR      0x14u

#define SR_EOP     (1u << 0)
#define SR_OPERR   (1u << 1)
#define SR_PROGERR (1u << 3)
#define SR_WRPERR  (1u << 4)
#define SR_PGAERR  (1u << 5)
#define SR_SIZERR  (1u << 6)
#define SR_PGSERR  (1u << 7)
#define SR_MISERR  (1u << 8)
#define SR_FASTERR (1u << 9)
#define SR_BSY     (1u << 16)

/* While one of these is set, the controller refuses the next program or
 * erase and sets PGSERR: software has to clear them first (RM0440: "check
 * and clear all error programming flags due to a previous programming"). */
#define SR_PROG_ERRORS (SR_PROGERR | SR_WRPERR | SR_PGAERR | SR_SIZERR | SR_PGSERR | SR_MISERR | SR_FASTERR)

#define CR_PG       (1u << 0)
#define CR_PER      (1u << 1)
#define CR_MER1     (1u << 2)
#define CR_STRT     (1u << 16)
#define CR_OPTSTRT  (1u << 17)
#define CR_EOPIE    (1u << 24)
#define CR_ERRIE    (1u << 25)
#define CR_OBL_LAUNCH (1u << 27)
#define CR_OPTLOCK  (1u << 30)
#define CR_LOCK     (1u << 31)

#define KEY1 0x45670123u
#define KEY2 0xCDEF89ABu
#define OPTKEY1 0x08192A3Bu
#define OPTKEY2 0x4C5D6E7Fu

#define T_PROG_PS   81690000ull           /* 81.69 us per double word */
#define T_ERASE_PS  22020000000ull        /* 22.02 ms per page */

enum { OP_NONE, OP_PROGRAM, OP_PAGE_ERASE, OP_MASS_ERASE };

static uint32_t *fr(struct sl_soc *s) { return s->regs[s->pi_flash]; }

static void update_irq(struct sl_soc *s)
{
    uint32_t sr = fr(s)[SR / 4], cr = fr(s)[CR / 4];
    int level = ((cr & CR_EOPIE) && (sr & SR_EOP)) || ((cr & CR_ERRIE) && (sr & SR_OPERR));
    cm_set_irq(&s->cpu, 4, level);
}

static void error(struct sl_soc *s, uint32_t flag, const char *why)
{
    fr(s)[SR / 4] |= flag;
    if (fr(s)[CR / 4] & CR_ERRIE) fr(s)[SR / 4] |= SR_OPERR;
    soc_log(s, LOG_FLASH_SEQ, "Flash: %s (PC 0x%08X)", why, s->cpu.r[15]);
    update_irq(s);
}

/* Nonzero, and PGSERR set, when an earlier error flag blocks the operation. */
static int refused(struct sl_soc *s, const char *op)
{
    uint32_t pending = fr(s)[SR / 4] & SR_PROG_ERRORS;
    if (!pending) return 0;
    char why[200];
    snprintf(why, sizeof why, "%s refused: FLASH_SR still has error flags %04X from an earlier operation. "
             "Write them back to FLASH_SR to clear them before each program or erase", op, pending);
    error(s, SR_PGSERR, why);
    return 1;
}

void flash_reset(struct sl_soc *s)
{
    s->flash_keys = 0;
    s->flash_locked_hard = 0;
    s->flash_half = 0;
    s->flash_op = OP_NONE;
}

void flash_check_latency(struct sl_soc *s)
{
    uint32_t *pw = s->regs[s->pi_pwr];
    uint32_t vos = (pw[0] >> 9) & 3u, boost = !((pw[0x80 / 4] >> 8) & 1u);
    uint32_t mhz = (s->hclk + 999999u) / 1000000u, need;
    if (vos == 2) need = mhz <= 12 ? 0 : mhz <= 24 ? 1 : 2;
    else if (boost) need = mhz <= 34 ? 0 : mhz <= 68 ? 1 : mhz <= 102 ? 2 : mhz <= 136 ? 3 : 4;
    else need = mhz <= 30 ? 0 : mhz <= 60 ? 1 : mhz <= 90 ? 2 : mhz <= 120 ? 3 : 4;
    uint32_t ws = fr(s)[ACR / 4] & 15u;
    if (ws < need)
        soc_log(s, LOG_FLASH_WS, "HCLK is %u MHz but FLASH_ACR.LATENCY is %u: this speed needs at least %u "
                "wait states. A real chip would read wrong instructions and crash.", mhz, ws, need);
}

uint32_t flash_read(struct sl_soc *s, uint32_t off)
{
    if (off == KEYR || off == OPTKEYR || off == 0x04u) return 0;   /* write-only */
    return fr(s)[off / 4];
}

static void start_op(struct sl_soc *s, int op, uint32_t addr, uint64_t duration)
{
    s->flash_op = op;
    s->flash_op_addr = addr;
    fr(s)[SR / 4] |= SR_BSY;
    soc_sched(s, EV_FLASH, soc_now_ps(s) + duration);
}

void flash_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask)
{
    uint32_t *r = fr(s);
    switch (off) {
    case KEYR:
        if (s->flash_locked_hard || !(r[CR / 4] & CR_LOCK)) return;
        if (mask != 0xFFFFFFFFu) goto wrong_key;
        if (s->flash_keys == 0 && v == KEY1) { s->flash_keys = 1; return; }
        if (s->flash_keys == 1 && v == KEY2) { s->flash_keys = 0; r[CR / 4] &= ~CR_LOCK; return; }
    wrong_key:
        s->flash_locked_hard = 1;
        soc_log(s, LOG_FLASH_KEY, "Flash: wrong key sequence written to FLASH_KEYR. FLASH_CR stays "
                "locked until the next reset (PC 0x%08X)", s->cpu.r[15]);
        return;
    case OPTKEYR:
        if (r[CR / 4] & CR_LOCK) return;
        if (s->flash_keys == 0 && v == OPTKEY1) { s->flash_keys = 2; return; }
        if (s->flash_keys == 2 && v == OPTKEY2) { s->flash_keys = 0; r[CR / 4] &= ~CR_OPTLOCK; return; }
        s->flash_keys = 0;
        return;
    case SR:
        r[SR / 4] &= ~(v & mask & 0xC3FBu);       /* write 1 to clear EOP and errors */
        update_irq(s);
        return;
    case CR: {
        if (r[CR / 4] & CR_LOCK) return;          /* locked: writes are ignored */
        uint32_t wm = s->wmask[s->pi_flash][CR / 4] & mask & ~(CR_LOCK | CR_OPTLOCK);
        uint32_t nv = (r[CR / 4] & ~wm) | (v & wm);
        if (v & mask & CR_LOCK) nv |= CR_LOCK;     /* LOCK and OPTLOCK can only be set */
        if (v & mask & CR_OPTLOCK) nv |= CR_OPTLOCK;
        nv &= ~(CR_STRT | CR_OPTSTRT | CR_OBL_LAUNCH);
        r[CR / 4] = nv;
        if (v & mask & CR_STRT) {
            if (r[SR / 4] & SR_BSY) return;
            if ((nv & (CR_MER1 | CR_PER)) && refused(s, "erase")) return;
            if (nv & CR_MER1) {
                start_op(s, OP_MASS_ERASE, 0, T_ERASE_PS);
            } else if (nv & CR_PER) {
                uint32_t page = (nv >> 3) & 0x7Fu;
                if (page >= SL_FLASH_PAGES) { error(s, SR_PGSERR, "page erase of a page that does not exist"); return; }
                start_op(s, OP_PAGE_ERASE, page * SL_FLASH_PAGE, T_ERASE_PS);
            } else {
                error(s, SR_PGSERR, "STRT set without PER or MER1");
            }
        }
        if (v & mask & CR_OPTSTRT)
            soc_log(s, LOG_FLASH_PROG, "Flash: option byte programming is not emulated; ignored");
        if ((v & mask & CR_OBL_LAUNCH) && s->sysreset) s->sysreset(s->host);
        update_irq(s);
        return;
    }
    case ACR: {
        uint32_t wm = s->wmask[s->pi_flash][ACR / 4] & mask;
        r[ACR / 4] = ((r[ACR / 4] & ~wm) | (v & wm)) & ~((1u << 11) | (1u << 12));   /* cache resets self-clear */
        flash_check_latency(s);
        return;
    }
    default: {
        uint32_t wm = s->wmask[s->pi_flash][off / 4] & mask;
        r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
        return;
    }
    }
}

/* A store to flash memory. Returns nonzero for a bus error. */
int flash_mem_write(struct sl_soc *s, uint32_t addr, int size, uint32_t v)
{
    uint32_t *r = fr(s);
    uint32_t off = addr >= SL_FLASH_BASE ? addr - SL_FLASH_BASE : addr;
    if (off >= SL_FLASH_SIZE) return 1;

    if (!(r[CR / 4] & CR_PG) || (r[CR / 4] & CR_LOCK)) {
        error(s, SR_PGSERR, "write to flash memory without FLASH_CR.PG set (flash is written through the "
              "controller: unlock, set PG, then write two words)");
        return 0;
    }
    if (r[SR / 4] & SR_BSY) {
        error(s, SR_PGSERR, "write to flash memory while a flash operation is still running (BSY)");
        return 0;
    }
    if (refused(s, "programming")) {
        s->flash_half = 0;
        return 0;
    }
    if (size != 4) {
        s->flash_half = 0;
        error(s, SR_SIZERR, "flash can only be programmed with word writes, two per double word");
        return 0;
    }
    if (!s->flash_half) {
        if (off & 7u) {
            error(s, SR_PGAERR, "flash programming must start at an 8-byte aligned address");
            return 0;
        }
        s->flash_half = 1;
        s->flash_addr = off;
        s->flash_word0 = v;
        return 0;
    }
    s->flash_half = 0;
    if (off != s->flash_addr + 4u) {
        error(s, SR_PGAERR, "the second word of a double word must follow the first");
        return 0;
    }
    uint8_t *dst = s->flash + s->flash_addr;
    uint32_t w0 = s->flash_word0, w1 = v;
    int erased = 1;
    for (int i = 0; i < 8; i++) if (dst[i] != 0xFF) erased = 0;
    if (!erased && (w0 | w1) != 0) {
        error(s, SR_PROGERR, "double word programmed over data that was not erased");
        return 0;
    }
    memcpy(s->flash_buf, &w0, 4);
    memcpy(s->flash_buf + 4, &w1, 4);
    start_op(s, OP_PROGRAM, s->flash_addr, T_PROG_PS);
    return 0;
}

void flash_event(struct sl_soc *s)
{
    uint32_t *r = fr(s);
    switch (s->flash_op) {
    case OP_PROGRAM:
        memcpy(s->flash + s->flash_op_addr, s->flash_buf, 8);
        s->flash_dirty[s->flash_op_addr / SL_FLASH_PAGE] = 1;
        break;
    case OP_PAGE_ERASE:
        memset(s->flash + s->flash_op_addr, 0xFF, SL_FLASH_PAGE);
        s->flash_dirty[s->flash_op_addr / SL_FLASH_PAGE] = 1;
        break;
    case OP_MASS_ERASE:
        memset(s->flash, 0xFF, SL_FLASH_SIZE);
        memset(s->flash_dirty, 1, sizeof s->flash_dirty);
        break;
    default:
        break;
    }
    s->flash_op = OP_NONE;
    r[SR / 4] &= ~SR_BSY;
    if (r[CR / 4] & CR_EOPIE) r[SR / 4] |= SR_EOP;
    update_irq(s);
}
