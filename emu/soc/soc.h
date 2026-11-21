/*
 * Solderless - the emulated STM32G431RB: memory map, clocks, peripherals.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Everything here runs on one thread (the VM thread). vm.c owns threads,
 * host queues and real-time pacing; it calls into the chip through the
 * functions at the end of this file.
 */
#ifndef SL_SOC_H
#define SL_SOC_H

#include "../core/armv7m.h"
#include "../gen/g431_data.h"
#include "../board/board.h"

#include <stdint.h>

#define SL_FLASH_BASE     0x08000000u
#define SL_FLASH_SIZE     0x00020000u   /* 128 KiB */
#define SL_FLASH_PAGE     0x800u        /* 2 KiB pages */
#define SL_FLASH_PAGES    (SL_FLASH_SIZE / SL_FLASH_PAGE)
#define SL_SRAM_BASE      0x20000000u
#define SL_SRAM_SIZE      0x00008000u   /* SRAM1 16K + SRAM2 6K + CCM SRAM 10K */
#define SL_CCM_BASE       0x10000000u
#define SL_CCM_SIZE       0x00002800u
#define SL_CCM_OFFSET     0x5800u       /* CCM SRAM also appears at 0x2000_5800 */
#define SL_SYSMEM_BASE    0x1FFF0000u
#define SL_SYSMEM_SIZE    0x00008000u

#define SL_PS_PER_S       1000000000000ull
#define SL_NEVER          UINT64_MAX

#define SL_NUM_IRQ        102
#define SL_NUM_PORTS      7             /* GPIOA..GPIOG */
#define SL_NUM_USART      5             /* USART1, USART2, USART3, UART4, LPUART1 */
#define SL_NUM_TIM        10            /* TIM1-4, TIM6-8, TIM15-17 */
#define SL_NUM_ADC        2
#define SL_NUM_I2C        3             /* I2C1, I2C2, I2C3 */
#define SL_NUM_SPI        3             /* SPI1, SPI2, SPI3 */

/* Scheduled events */
enum {
    EV_HSI, EV_HSE, EV_PLL, EV_LSI, EV_LSE, EV_HSI48,
    EV_FLASH,
    EV_USART_TX0, EV_USART_TX_LAST = EV_USART_TX0 + SL_NUM_USART - 1,
    EV_TERM_RX,
    EV_BOARD,
    EV_TIM0, EV_TIM_LAST = EV_TIM0 + SL_NUM_TIM - 1,
    EV_ADC0, EV_ADC_LAST = EV_ADC0 + SL_NUM_ADC - 1,
    EV_I2C0, EV_I2C_LAST = EV_I2C0 + SL_NUM_I2C - 1,
    EV_SPI0, EV_SPI_LAST = EV_SPI0 + SL_NUM_SPI - 1,
    EV_COUNT
};

struct sl_usart {
    int periph;                 /* index into sl_g431_periph */
    int lp;                     /* LPUART */
    int irq;
    uint32_t cr1, cr2, cr3, brr, gtpr, rtor, presc;
    uint32_t isr;               /* error and status flags kept here */
    uint32_t rdr;
    uint32_t tdr;
    int tdr_full;               /* TDR holds a byte not yet in the shift register */
    int shifting;               /* shift register busy */
    uint32_t shift;
    uint32_t shift_baud;        /* baud rate the byte in the shift register uses */
};

/* What a timer channel puts on a pin */
struct sl_tim_out {
    uint32_t duty_q16, freq_mhz;
    int level;
};

struct sl_tim {
    const char *name;
    int pi, adv, bits32, apb2, nch;
    int irq_up, irq_cc;
    int running, polled, moe_warned, enc_warned;
    int ti[2];                  /* TI1, TI2 as last seen on their pins */
    uint64_t t0, t_seen;        /* counter was at position pos0 at t0; flags known up to t_seen */
    uint64_t pos0, tick;        /* tick: ps per count */
    uint32_t psc, arr, rcr, rep, ccr[4];   /* active (shadow) values */
};

struct sl_adc {
    const char *name;
    int idx, pi;
    int converting, k, polled, ovr_logged;
    uint64_t t_next;            /* start of the conversion in progress */
    uint64_t reg_on_at, cal_until, rdy_at;
    uint32_t dr;
};

struct sl_i2c {
    const char *name;
    int pi, irq_ev, irq_er;
    uint32_t isr;               /* ISR flags (BUSY and DIR computed) */
    int state;                  /* I2C_* in i2c.c */
    int read, addr, nbytes, count, reload, autoend, stop_req;
    int scl, sda;               /* pin slots of this transfer */
    uint8_t txdr, rxdr, shift, pending;
    uint64_t bit_ps;            /* one SCL period */
    uint32_t scl_hz;
};

struct sl_spi {
    const char *name;
    int pi, irq;
    uint8_t txf[4], rxf[4];     /* the FIFOs, oldest byte first */
    int txn, rxn;
    int busy;                   /* a frame on the wire */
    uint32_t cur, hz;
    int cur_bits;
    uint32_t flags;             /* MODF, OVR */
    int modf_seen, ovr_dr_read; /* the clearing sequences */
    int mosi_level;
};

struct sl_idle {
    uint32_t addr, value, pc, xpsr;
    uint64_t changes;
    uint32_t regs[15];
    uint64_t cyc;
    int count;
    int periph;
};

struct sl_soc {
    struct cm_cpu cpu;
    struct cm_bus bus;
    struct cm_region r_flash, r_alias, r_sram, r_ccm, r_sysmem;

    uint8_t flash[SL_FLASH_SIZE];
    uint8_t sram[SL_SRAM_SIZE];
    uint8_t sysmem[SL_SYSMEM_SIZE];
    uint8_t flash_dirty[SL_FLASH_PAGES];

    /* Register storage for every peripheral, by index into sl_g431_periph */
    uint32_t *regs[96];
    uint32_t *wmask[96];
    uint8_t clk_on[96];
    int np;
    int pi_rcc, pi_pwr, pi_flash, pi_syscfg, pi_dbgmcu, pi_gpio[SL_NUM_PORTS];
    int pi_exti, pi_dac1, pi_adc_common;

    /* Interrupt lines with several sources (TIM1_UP and TIM16, ...): one bit per source */
    uint64_t irq_src[SL_NUM_IRQ];

    /* Time: t_ps is the virtual time at cpu.cycles == t_cyc */
    uint64_t t_ps, t_cyc, t_rem;
    uint32_t hclk;
    uint64_t ev[EV_COUNT];

    /* Clocks (Hz) */
    uint32_t sysclk, pclk1, pclk2, pllr, pllq, pllp;
    uint32_t hse_hz;            /* crystal fitted on the board, 0 if none */
    int lse_fitted;

    /* Flash controller */
    int flash_keys;             /* key sequence progress: 0, 1 */
    int flash_locked_hard;      /* wrong key: locked until reset */
    int flash_half;             /* first word of a double word written */
    uint32_t flash_addr, flash_word0;
    int flash_op;               /* operation in progress: 0 none, 1 program, 2 page erase, 3 mass erase */
    uint32_t flash_op_addr;
    uint8_t flash_buf[8];

    /* GPIO */
    uint32_t pin_ext_drive[SL_NUM_PORTS];    /* pins driven from outside */
    uint32_t pin_ext_level[SL_NUM_PORTS];
    uint32_t gpio_lock_bits[SL_NUM_PORTS];  /* LCKR: pins in the lock sequence */
    int gpio_lock_step[SL_NUM_PORTS];       /* 4 when locked */

    /* USARTs and the serial terminal on PA2/PA3 */
    struct sl_usart usart[SL_NUM_USART];
    uint32_t term_baud;
    int term_rx_busy;           /* a byte from the terminal is on the wire */
    uint8_t term_rx_byte;

    /* Timers, ADC, DAC */
    struct sl_tim tim[SL_NUM_TIM];
    struct sl_adc adc[SL_NUM_ADC];
    uint32_t dac_dhr[2], dac_dor[2];
    struct sl_i2c i2c[SL_NUM_I2C];
    struct sl_spi spi[SL_NUM_SPI];
    int rd_width;               /* bytes of the read being made (DR packing) */
    int spi_hw_frame;           /* an on-chip SPI frame is being delivered bit by bit */

    /* The breadboard */
    struct sl_board board;

    /* Idle detection */
    struct sl_idle idle;
    int idle_poll;              /* the CPU is polling a register that cannot change before the next event */

    /* Reset flags for RCC_CSR */
    uint32_t reset_flags;

    /* Host hooks (set by vm.c) */
    void *host;
    int (*term_rx_pop)(void *host, uint8_t *b);      /* next byte typed in the terminal */
    int (*term_rx_ready)(void *host);                 /* bytes waiting */
    void (*term_tx_push)(void *host, uint8_t b);      /* byte for the terminal screen */
    void (*log)(void *host, int key, const char *msg);
    void (*sysreset)(void *host);                     /* SYSRESETREQ */
};

/* Setup and reset */
void soc_init(struct sl_soc *s);
void soc_reset(struct sl_soc *s, int power_on);

/* Time */
uint64_t soc_now_ps(const struct sl_soc *s);
void soc_time_commit(struct sl_soc *s);
void soc_sched(struct sl_soc *s, int ev, uint64_t at_ps);
uint64_t soc_next_event_ps(struct sl_soc *s);
void soc_skip_to(struct sl_soc *s, uint64_t t_ps);
void soc_process_events(struct sl_soc *s);
void soc_set_hclk(struct sl_soc *s, uint32_t hz);
uint64_t soc_muldiv(uint64_t a, uint64_t b, uint64_t c);

/* Runs one batch of instructions, up to the next event. Returns cycles run. */
uint32_t soc_run_batch(struct sl_soc *s, uint32_t max);

/* Terminal: starts receiving the next typed byte if the line is free. */
void soc_term_kick(struct sl_soc *s);
/* True while a byte from or to the terminal is in flight. */
int soc_term_busy(struct sl_soc *s);

/* Called by RCC: enable bits changed; reset bits written */
void soc_clock_gates_changed(struct sl_soc *s);
void soc_apply_resets(struct sl_soc *s, uint32_t rcc_off, uint32_t bits);

/* Debug: side-effect-free register read */
uint32_t soc_peek32(struct sl_soc *s, uint32_t addr);

/* Interrupt line shared by several sources: the line is high while any source is. */
void soc_irq(struct sl_soc *s, int irq, int src, int level);

/* Log with deduplication by key */
void soc_log(struct sl_soc *s, int key, const char *fmt, ...);

/* Peripheral modules */
void rcc_reset(struct sl_soc *s, int power_on);
uint32_t rcc_read(struct sl_soc *s, uint32_t off);
void rcc_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask);
void rcc_event(struct sl_soc *s, int ev);
void rcc_update_clocks(struct sl_soc *s);
uint32_t rcc_usart_kernel_hz(struct sl_soc *s, int idx);
void pwr_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask);

void flash_reset(struct sl_soc *s);
uint32_t flash_read(struct sl_soc *s, uint32_t off);
void flash_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask);
int flash_mem_write(struct sl_soc *s, uint32_t addr, int size, uint32_t v);
void flash_event(struct sl_soc *s);
void flash_check_latency(struct sl_soc *s);

void gpio_reset(struct sl_soc *s);
uint32_t gpio_read(struct sl_soc *s, int port, uint32_t off);
void gpio_write(struct sl_soc *s, int port, uint32_t off, uint32_t v, uint32_t mask);
int gpio_pin_af(struct sl_soc *s, int port, int pin);    /* -1 unless in alternate-function mode */
uint32_t gpio_idr(struct sl_soc *s, int port);

void usart_init(struct sl_soc *s);
void usart_reset(struct sl_soc *s, int idx);
uint32_t usart_read(struct sl_soc *s, int idx, uint32_t off, int effects);
void usart_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask);
void usart_tx_event(struct sl_soc *s, int idx);
void usart_term_rx_event(struct sl_soc *s);
int usart_tx_busy(struct sl_soc *s, int idx);
int usart_index_of_periph(struct sl_soc *s, int pi);

void exti_reset(struct sl_soc *s);
uint32_t exti_read(struct sl_soc *s, uint32_t off);
void exti_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask);
void exti_gpio(struct sl_soc *s, int port, int pin, int level);

void tim_init(struct sl_soc *s);
void tim_reset(struct sl_soc *s, int idx);
int tim_index_of_periph(struct sl_soc *s, int pi);
uint32_t tim_read(struct sl_soc *s, int idx, uint32_t off, int effects);
void tim_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask);
void tim_event(struct sl_soc *s, int idx);
void tim_poll_hint(struct sl_soc *s, int idx);
void tim_clocks_changed(struct sl_soc *s);
int tim_output(struct sl_soc *s, int pi, int ch, int neg, struct sl_tim_out *o);
void tim_input_edge(struct sl_soc *s, int pi, int ch, int level);

void adc_init(struct sl_soc *s);
void adc_reset(struct sl_soc *s, int idx);
int adc_index_of_periph(struct sl_soc *s, int pi);
uint32_t adc_read(struct sl_soc *s, int idx, uint32_t off, int effects);
void adc_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask);
void adc_event(struct sl_soc *s, int idx);
void adc_poll_hint(struct sl_soc *s, int idx);

void i2c_init(struct sl_soc *s);
void i2c_reset(struct sl_soc *s, int idx);
int i2c_index_of_periph(struct sl_soc *s, int pi);
uint32_t i2c_read(struct sl_soc *s, int idx, uint32_t off, int effects);
void i2c_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask);
void i2c_event(struct sl_soc *s, int idx);
uint32_t rcc_i2c_kernel_hz(struct sl_soc *s, int idx);
void spi_init(struct sl_soc *s);
void spi_reset(struct sl_soc *s, int idx);
int spi_index_of_periph(struct sl_soc *s, int pi);
uint32_t spi_read(struct sl_soc *s, int idx, uint32_t off, int effects);
void spi_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask);
void spi_event(struct sl_soc *s, int idx);
int spi_pin_out(struct sl_soc *s, int pi, const char *sig, int *level);

void dac_reset(struct sl_soc *s);
uint32_t dac_read(struct sl_soc *s, uint32_t off);
void dac_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask);
int dac_pin_out(struct sl_soc *s, int port, int pin, int *mv, int *g_us);

/* Log keys (deduplication) */
enum {
    LOG_CLOCK_OFF = 1, LOG_FLASH_WS, LOG_BOOST, LOG_VCO, LOG_FLASH_SEQ, LOG_FLASH_PROG,
    LOG_FLASH_KEY, LOG_BAUD, LOG_OVERRUN, LOG_LOCKUP, LOG_HSE, LOG_LSE, LOG_BDCR,
    LOG_USART_LOST, LOG_PLL_SRC, LOG_BUS_ERROR,
    LOG_FLOATING, LOG_UNDEFINED, LOG_SHORT, LOG_PIN_CURRENT, LOG_TIMER, LOG_ADC, LOG_ADC_PIN,
    LOG_I2C_SPEED, LOG_I2C_CLASH, LOG_I2C_PINS, LOG_I2C_PULLUP, LOG_I2C_PUSHPULL, LOG_I2C_NACK,
    LOG_SPI_PINS, LOG_SPI_MODF, LOG_SPI_OVR, LOG_SPI_MODE, LOG_SPI_SPEED, LOG_SPI_ORDER, LOG_SPI_MISO,
    LOG_PART = 40,      /* + part kind * 8 + the part's message code */
};

#endif
