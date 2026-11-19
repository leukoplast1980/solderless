/*
 * Solderless - extended interrupt controller, GPIO lines 0-15 (RM0440
 * chapter 15) and SYSCFG's port selection for them.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Line n can listen to pin n of exactly one port, chosen in
 * SYSCFG_EXTICR: PA0 and PB0 share line 0, so only one of them can
 * interrupt. Lines 5-9 and 10-15 share one interrupt each.
 */
#include "soc.h"

#define IMR1    0x00u
#define EMR1    0x04u
#define RTSR1   0x08u
#define FTSR1   0x0Cu
#define SWIER1  0x10u
#define PR1     0x14u

/* Interrupt of each GPIO line (RM0440 table 97) */
static int line_irq(int line)
{
    static const int irq[5] = { 6, 7, 8, 9, 10 };
    if (line < 5) return irq[line];
    return line < 10 ? 23 : 40;
}

static uint32_t *regs(struct sl_soc *s) { return s->regs[s->pi_exti]; }

static void update_irqs(struct sl_soc *s)
{
    uint32_t act = regs(s)[PR1 / 4] & regs(s)[IMR1 / 4] & 0xFFFFu;
    for (int l = 0; l < 5; l++) cm_set_irq(&s->cpu, line_irq(l), (act >> l) & 1u);
    cm_set_irq(&s->cpu, 23, (act & 0x03E0u) != 0);
    cm_set_irq(&s->cpu, 40, (act & 0xFC00u) != 0);
}

/* The port SYSCFG_EXTICR selects for a line */
static int line_port(struct sl_soc *s, int line)
{
    uint32_t cr = s->regs[s->pi_syscfg][(0x08u + 4u * (uint32_t)(line / 4)) / 4];
    return (int)((cr >> (4 * (line % 4))) & 0xFu);
}

void exti_gpio(struct sl_soc *s, int port, int pin, int level)
{
    if (line_port(s, pin) != port) return;
    uint32_t *r = regs(s), bit = 1u << pin;
    uint32_t trig = level ? r[RTSR1 / 4] : r[FTSR1 / 4];
    if (!(trig & bit)) return;
    if (r[IMR1 / 4] & bit) {
        r[PR1 / 4] |= bit;
        update_irqs(s);
    }
    if (r[EMR1 / 4] & bit) cm_wake(&s->cpu);        /* event: wakes WFE */
}

uint32_t exti_read(struct sl_soc *s, uint32_t off)
{
    if (off == SWIER1) return regs(s)[SWIER1 / 4] & regs(s)[PR1 / 4];
    return regs(s)[off / 4];
}

void exti_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask)
{
    uint32_t *r = regs(s);
    uint32_t wm = s->wmask[s->pi_exti][off / 4] & mask;
    switch (off) {
    case PR1:
        r[PR1 / 4] &= ~(v & wm);                    /* write 1 to clear */
        break;
    case SWIER1: {
        uint32_t set = v & wm & r[IMR1 / 4];
        r[SWIER1 / 4] |= v & wm;
        r[PR1 / 4] |= set;
        break;
    }
    default:
        r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
        break;
    }
    update_irqs(s);
}

void exti_reset(struct sl_soc *s)
{
    for (int l = 0; l < 5; l++) cm_set_irq(&s->cpu, line_irq(l), 0);
    cm_set_irq(&s->cpu, 23, 0);
    cm_set_irq(&s->cpu, 40, 0);
}
