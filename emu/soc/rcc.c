/*
 * Solderless - RCC (reset and clock control) and PWR.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Register layout and behaviour from RM0440, chapters 6 (PWR) and 7 (RCC).
 * Oscillators become ready after a start-up time; the clock tree is
 * computed from the registers and drives the CPU clock and peripherals.
 */
#include "soc.h"

/* RCC register offsets */
#define CR        0x00u
#define ICSCR     0x04u
#define CFGR      0x08u
#define PLLCFGR   0x0Cu
#define CIER      0x18u
#define CIFR      0x1Cu
#define CICR      0x20u
#define CCIPR     0x88u
#define BDCR      0x90u
#define CSR       0x94u
#define CRRCR     0x98u

#define CR_HSION    (1u << 8)
#define CR_HSIKERON (1u << 9)
#define CR_HSIRDY   (1u << 10)
#define CR_HSEON    (1u << 16)
#define CR_HSERDY   (1u << 17)
#define CR_HSEBYP   (1u << 18)
#define CR_PLLON    (1u << 24)
#define CR_PLLRDY   (1u << 25)

#define BDCR_LSEON  (1u << 0)
#define BDCR_LSERDY (1u << 1)
#define BDCR_BDRST  (1u << 16)

#define CSR_LSION   (1u << 0)
#define CSR_LSIRDY  (1u << 1)
#define CSR_RMVF    (1u << 23)

#define HSI_HZ      16000000u
#define LSI_HZ      32000u
#define LSE_HZ      32768u

/* PWR register offsets */
#define PWR_CR1   0x00u
#define PWR_CR5   0x80u
#define PWR_DBP   (1u << 8)
#define PWR_R1MODE (1u << 8)

/* Start-up times (datasheet typical values, rounded) */
#define T_HSI_PS    1000000ull            /* 1 us */
#define T_HSE_PS    2000000000ull         /* 2 ms crystal start-up */
#define T_PLL_PS    40000000ull           /* 40 us lock */
#define T_LSI_PS    80000000ull           /* 80 us */
#define T_LSE_PS    2000000000000ull      /* 2 s: 32 kHz crystals start slowly */
#define T_HSI48_PS  3000000ull

static uint32_t *rcc(struct sl_soc *s) { return s->regs[s->pi_rcc]; }
static uint32_t *pwr(struct sl_soc *s) { return s->regs[s->pi_pwr]; }

static int sysclk_source(struct sl_soc *s) { return (int)((rcc(s)[CFGR / 4] >> 2) & 3u); }

static uint32_t pll_input_hz(struct sl_soc *s)
{
    uint32_t v = rcc(s)[PLLCFGR / 4];
    switch (v & 3u) {
    case 2: return HSI_HZ;
    case 3: return s->hse_hz;
    default: return 0;
    }
}

static int pll_source_ready(struct sl_soc *s)
{
    uint32_t cr = rcc(s)[CR / 4];
    switch (rcc(s)[PLLCFGR / 4] & 3u) {
    case 2: return (cr & CR_HSIRDY) != 0;
    case 3: return (cr & CR_HSERDY) != 0;
    default: return 0;
    }
}

static void update_irq(struct sl_soc *s)
{
    cm_set_irq(&s->cpu, 5, (rcc(s)[CIFR / 4] & rcc(s)[CIER / 4]) != 0);
}

static void ready_flag(struct sl_soc *s, uint32_t cier_bit)
{
    if (rcc(s)[CIER / 4] & cier_bit) {
        rcc(s)[CIFR / 4] |= cier_bit;
        update_irq(s);
    }
}

void rcc_update_clocks(struct sl_soc *s)
{
    uint32_t v = rcc(s)[PLLCFGR / 4];
    uint32_t m = ((v >> 4) & 15u) + 1u, n = (v >> 8) & 0x7Fu;
    uint32_t in = pll_input_hz(s) / m;
    uint64_t vco = (uint64_t)in * n;
    s->pllr = (uint32_t)(vco / ((((v >> 25) & 3u) + 1u) * 2u));
    s->pllq = (uint32_t)(vco / ((((v >> 21) & 3u) + 1u) * 2u));
    uint32_t pdiv = (v >> 27) & 31u;
    s->pllp = (uint32_t)(vco / (pdiv >= 2 ? pdiv : ((v >> 17) & 1u) ? 17u : 7u));

    switch (sysclk_source(s)) {
    case 2: s->sysclk = s->hse_hz; break;
    case 3: s->sysclk = s->pllr; break;
    default: s->sysclk = HSI_HZ; break;
    }
    if (!s->sysclk) s->sysclk = HSI_HZ;

    uint32_t cfgr = rcc(s)[CFGR / 4];
    uint32_t hpre = (cfgr >> 4) & 15u, p1 = (cfgr >> 8) & 7u, p2 = (cfgr >> 11) & 7u;
    static const uint32_t hdiv[8] = { 2, 4, 8, 16, 64, 128, 256, 512 };
    uint32_t hclk = hpre & 8u ? s->sysclk / hdiv[hpre & 7u] : s->sysclk;
    s->pclk1 = p1 & 4u ? hclk >> ((p1 & 3u) + 1u) : hclk;
    s->pclk2 = p2 & 4u ? hclk >> ((p2 & 3u) + 1u) : hclk;
    soc_set_hclk(s, hclk);

    if (s->sysclk > 150000000u && (pwr(s)[PWR_CR5 / 4] & PWR_R1MODE))
        soc_log(s, LOG_BOOST, "SYSCLK is %u MHz, but above 150 MHz the G431 needs range 1 boost mode "
                "(PWR_CR5.R1MODE = 0). Out of spec; real chips may misbehave.", s->sysclk / 1000000u);
    flash_check_latency(s);
}

static void check_pll_config(struct sl_soc *s)
{
    uint32_t v = rcc(s)[PLLCFGR / 4];
    uint32_t m = ((v >> 4) & 15u) + 1u, n = (v >> 8) & 0x7Fu;
    uint32_t in = pll_input_hz(s) / m;
    uint64_t vco = (uint64_t)in * n;
    if ((v & 3u) < 2)
        soc_log(s, LOG_PLL_SRC, "PLL switched on without an input clock (PLLCFGR.PLLSRC): it will never lock");
    else if (in < 2660000u || in > 16000000u || n < 8 || n > 127 || vco < 96000000u || vco > 344000000u)
        soc_log(s, LOG_VCO, "PLL settings out of range: input %u kHz (2.66-16 MHz), N=%u (8-127), "
                "VCO %u MHz (96-344 MHz)", in / 1000u, n, (uint32_t)(vco / 1000000u));
}

void rcc_event(struct sl_soc *s, int ev)
{
    uint32_t *r = rcc(s);
    switch (ev) {
    case EV_HSI:
        if (r[CR / 4] & CR_HSION) { r[CR / 4] |= CR_HSIRDY; ready_flag(s, 1u << 3); }
        break;
    case EV_HSE:
        if (r[CR / 4] & CR_HSEON) { r[CR / 4] |= CR_HSERDY; ready_flag(s, 1u << 4); }
        break;
    case EV_PLL:
        if ((r[CR / 4] & CR_PLLON) && pll_source_ready(s) && (r[PLLCFGR / 4] & 3u) >= 2) {
            r[CR / 4] |= CR_PLLRDY;
            ready_flag(s, 1u << 5);
        }
        break;
    case EV_LSI:
        if (r[CSR / 4] & CSR_LSION) { r[CSR / 4] |= CSR_LSIRDY; ready_flag(s, 1u << 0); }
        break;
    case EV_LSE:
        if (r[BDCR / 4] & BDCR_LSEON) { r[BDCR / 4] |= BDCR_LSERDY; ready_flag(s, 1u << 1); }
        break;
    case EV_HSI48:
        if (r[CRRCR / 4] & 1u) { r[CRRCR / 4] |= 2u; ready_flag(s, 1u << 10); }
        break;
    default:
        break;
    }
    /* A pending clock switch completes once its source is ready */
    uint32_t cfgr = r[CFGR / 4], sw = cfgr & 3u;
    if (sw != ((cfgr >> 2) & 3u)) {
        int ready = sw == 1 ? (r[CR / 4] & CR_HSIRDY) != 0
                  : sw == 2 ? (r[CR / 4] & CR_HSERDY) != 0
                  : sw == 3 ? (r[CR / 4] & CR_PLLRDY) != 0 : 0;
        if (ready) r[CFGR / 4] = (cfgr & ~0xCu) | (sw << 2);
    }
    rcc_update_clocks(s);
}

/* Called after the register file is back at its reset values. */
void rcc_reset(struct sl_soc *s, int power_on)
{
    uint32_t *r = rcc(s);
    r[ICSCR / 4] = 0x40400000u;                   /* HSITRIM 0x40, HSICAL 0x40 */
    r[CSR / 4] = (r[CSR / 4] & ~0xFE000000u) | (power_on ? 0x0C000000u : s->reset_flags);
    s->reset_flags = 0;
}

uint32_t rcc_read(struct sl_soc *s, uint32_t off)
{
    return rcc(s)[off / 4];
}

static uint32_t merge(uint32_t old, uint32_t v, uint32_t m) { return (old & ~m) | (v & m); }

void rcc_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask)
{
    uint32_t *r = rcc(s);
    uint32_t wm = s->wmask[s->pi_rcc][off / 4] & mask;
    uint32_t old = r[off / 4];
    uint64_t now = soc_now_ps(s);

    switch (off) {
    case CR: {
        uint32_t nv = merge(old, v, wm);
        int src = sysclk_source(s);
        int pll_src = (int)(r[PLLCFGR / 4] & 3u);
        int pll_on = (old & CR_PLLON) != 0;
        /* Oscillators in use cannot be switched off */
        if ((src == 1 || (pll_on && pll_src == 2)) && !(nv & CR_HSION)) nv |= CR_HSION;
        if ((src == 2 || (pll_on && pll_src == 3)) && !(nv & CR_HSEON)) nv |= CR_HSEON;
        if (src == 3 && !(nv & CR_PLLON)) nv |= CR_PLLON;
        r[CR / 4] = nv;

        if ((nv & CR_HSION) && !(old & CR_HSION)) soc_sched(s, EV_HSI, now + T_HSI_PS);
        if (!(nv & CR_HSION)) r[CR / 4] &= ~CR_HSIRDY;
        if ((nv & CR_HSEON) && !(old & CR_HSEON)) {
            if (s->hse_hz) soc_sched(s, EV_HSE, now + ((nv & CR_HSEBYP) ? T_HSI_PS : T_HSE_PS));
            else soc_log(s, LOG_HSE, "HSE switched on, but the board has no crystal on PF0/PF1: "
                         "HSERDY will never be set (set a crystal in the board settings)");
        }
        if (!(nv & CR_HSEON)) r[CR / 4] &= ~CR_HSERDY;
        if ((nv & CR_PLLON) && !(old & CR_PLLON)) {
            check_pll_config(s);
            soc_sched(s, EV_PLL, now + T_PLL_PS);
        }
        if (!(nv & CR_PLLON)) r[CR / 4] &= ~CR_PLLRDY;
        rcc_update_clocks(s);
        return;
    }
    case CFGR: {
        uint32_t nv = merge(old, v, wm);
        uint32_t sw = nv & 3u;
        int ready = sw == 1 ? (r[CR / 4] & CR_HSIRDY) != 0
                  : sw == 2 ? (r[CR / 4] & CR_HSERDY) != 0
                  : sw == 3 ? (r[CR / 4] & CR_PLLRDY) != 0 : 0;
        if (ready) nv = (nv & ~0xCu) | (sw << 2);
        r[CFGR / 4] = nv;
        rcc_update_clocks(s);
        return;
    }
    case PLLCFGR:
        r[off / 4] = merge(old, v, wm);
        rcc_update_clocks(s);
        return;
    case CIER:
        r[off / 4] = merge(old, v, wm);
        update_irq(s);
        return;
    case CICR:
        r[CIFR / 4] &= ~(v & mask);
        update_irq(s);
        return;
    case 0x28: case 0x2C: case 0x30: case 0x38: case 0x3C: case 0x40: {   /* reset registers */
        uint32_t nv = merge(old, v, wm);
        r[off / 4] = nv;
        if (nv & ~old) soc_apply_resets(s, off, nv & ~old);
        return;
    }
    case 0x48: case 0x4C: case 0x50: case 0x58: case 0x5C: case 0x60:     /* enable registers */
        r[off / 4] = merge(old, v, wm);
        soc_clock_gates_changed(s);
        return;
    case BDCR: {
        if (!(pwr(s)[PWR_CR1 / 4] & PWR_DBP)) {
            soc_log(s, LOG_BDCR, "RCC_BDCR is write-protected: set PWR_CR1.DBP first "
                    "(and enable the PWR clock in RCC_APB1ENR1)");
            return;
        }
        uint32_t nv = merge(old, v, wm);
        if (nv & BDCR_BDRST) nv = BDCR_BDRST;     /* backup domain reset */
        r[BDCR / 4] = nv;
        if ((nv & BDCR_LSEON) && !(old & BDCR_LSEON)) {
            if (s->lse_fitted) soc_sched(s, EV_LSE, now + T_LSE_PS);
            else soc_log(s, LOG_LSE, "LSE switched on, but the board has no 32.768 kHz crystal on "
                         "PC14/PC15: LSERDY will never be set");
        }
        if (!(nv & BDCR_LSEON)) r[BDCR / 4] &= ~BDCR_LSERDY;
        return;
    }
    case CSR: {
        uint32_t nv = merge(old, v, wm & 0x00FFFFFFu);
        if (v & mask & CSR_RMVF) nv &= 0x00FFFFFFu;
        nv &= ~CSR_RMVF;
        r[CSR / 4] = nv;
        if ((nv & CSR_LSION) && !(old & CSR_LSION)) soc_sched(s, EV_LSI, now + T_LSI_PS);
        if (!(nv & CSR_LSION)) r[CSR / 4] &= ~CSR_LSIRDY;
        return;
    }
    case CRRCR: {
        uint32_t nv = merge(old, v, wm);
        r[CRRCR / 4] = nv;
        if ((nv & 1u) && !(old & 1u)) soc_sched(s, EV_HSI48, now + T_HSI48_PS);
        if (!(nv & 1u)) r[CRRCR / 4] &= ~2u;
        return;
    }
    default:
        r[off / 4] = merge(old, v, wm);
        return;
    }
}

uint32_t rcc_usart_kernel_hz(struct sl_soc *s, int idx)
{
    static const int shift[SL_NUM_USART] = { 0, 2, 4, 6, 10 };
    uint32_t sel = (rcc(s)[CCIPR / 4] >> shift[idx]) & 3u;
    switch (sel) {
    case 0: return idx == 0 ? s->pclk2 : s->pclk1;
    case 1: return s->sysclk;
    case 2: return (rcc(s)[CR / 4] & CR_HSIRDY) ? HSI_HZ : 0;
    default: return (rcc(s)[BDCR / 4] & BDCR_LSERDY) ? LSE_HZ : 0;
    }
}

void pwr_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask)
{
    uint32_t wm = s->wmask[s->pi_pwr][off / 4] & mask;
    pwr(s)[off / 4] = merge(pwr(s)[off / 4], v, wm);
    if (off == PWR_CR1 || off == PWR_CR5) rcc_update_clocks(s);
}
