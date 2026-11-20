/*
 * Solderless - bare-metal test firmware for the peripherals and the board.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Runs on the emulated G431 (and on a real one). Prints results on USART2
 * at 115200 baud. Lines starting with '@' ask the host harness
 * (periph_test.c) to act on the board; the firmware then waits for one
 * byte back. Results: "ok NAME" or "FAIL NAME: ...".
 */
#include <stdint.h>

#define REG(a) (*(volatile uint32_t *)(a))

#define RCC         0x40021000u
#define AHB2ENR     REG(RCC + 0x4C)
#define APB1ENR1    REG(RCC + 0x58)
#define APB2ENR     REG(RCC + 0x60)
#define GPIOA       0x48000000u
#define GPIOC       0x48000800u
#define USART2      0x40004400u
#define TIM1        0x40012C00u
#define TIM2        0x40000000u
#define TIM3        0x40000400u
#define TIM4        0x40000800u
#define EXTI        0x40010400u
#define SYSCFG      0x40010000u
#define ADC1        0x50000000u
#define ADC2        0x50000100u
#define ADC12_CCR   REG(0x50000308u)
#define DAC1        0x50000800u
#define DEMCR       REG(0xE000EDFCu)
#define DWT_CTRL    REG(0xE0001000u)
#define CYCCNT      REG(0xE0001004u)
#define NVIC_ISER(n) REG(0xE000E100u + 4u * (n))

static volatile uint32_t tim2_irqs, tim3_cc_irqs, exti_irqs;
static int fails;

/* ---- serial ---- */
static void putc_(char c) { while (!(REG(USART2 + 0x1C) & (1u << 7))) {} REG(USART2 + 0x28) = (uint8_t)c; }
static void puts_(const char *s) { while (*s) putc_(*s++); }
static void putu(uint32_t v)
{
    char b[12]; int i = 0;
    do { b[i++] = (char)('0' + v % 10); v /= 10; } while (v);
    while (i) putc_(b[--i]);
}
static char getc_(void) { while (!(REG(USART2 + 0x1C) & (1u << 5))) {} return (char)REG(USART2 + 0x24); }
static void wait_tx_done(void) { while (!(REG(USART2 + 0x1C) & (1u << 6))) {} }

/* Asks the host to do something, waits for its answer byte */
static char ask(const char *req) { puts_("@"); puts_(req); puts_("\n"); wait_tx_done(); return getc_(); }

static void result(const char *name, int ok, const char *what, uint32_t got)
{
    if (ok) { puts_("ok "); puts_(name); puts_("\n"); return; }
    fails++;
    puts_("FAIL "); puts_(name); puts_(": "); puts_(what); puts_(" "); putu(got); puts_("\n");
}

static void delay_cycles(uint32_t n) { uint32_t t0 = CYCCNT; while (CYCCNT - t0 < n) {} }

static void pin_mode(uint32_t port, int pin, uint32_t mode, uint32_t af)
{
    REG(port) = (REG(port) & ~(3u << (2 * pin))) | (mode << (2 * pin));
    if (mode == 2) {
        uint32_t r = port + (pin < 8 ? 0x20u : 0x24u), sh = 4u * (uint32_t)(pin & 7);
        REG(r) = (REG(r) & ~(15u << sh)) | (af << sh);
    }
}

/* ---- interrupt handlers ---- */
void tim2_handler(void) { REG(TIM2 + 0x10) = ~1u; tim2_irqs++; }
void tim3_handler(void) { REG(TIM3 + 0x10) = ~2u; tim3_cc_irqs++; }
void exti15_10_handler(void) { REG(EXTI + 0x14) = 1u << 13; exti_irqs++; }

/* ---- tests ---- */
static void test_timer_rate(void)
{
    APB1ENR1 |= 1u;                                  /* TIM2 */
    REG(TIM2 + 0x28) = 15;                           /* 1 MHz */
    REG(TIM2 + 0x2C) = 999;                          /* 1 kHz updates */
    REG(TIM2 + 0x14) = 1;                            /* UG: load PSC */
    REG(TIM2 + 0x10) = 0;
    REG(TIM2 + 0x0C) = 1;                            /* UIE */
    NVIC_ISER(0) = 1u << 28;
    tim2_irqs = 0;
    REG(TIM2) = 1;
    delay_cycles(1600000);                           /* 100 ms at 16 MHz */
    REG(TIM2) = 0;
    uint32_t n = tim2_irqs;
    result("timer update rate", n >= 99 && n <= 101, "updates in 100 ms:", n);
}

static void test_compare_irq(void)
{
    APB1ENR1 |= 2u;                                  /* TIM3 */
    REG(TIM3 + 0x28) = 15;
    REG(TIM3 + 0x2C) = 999;
    REG(TIM3 + 0x34) = 250;
    REG(TIM3 + 0x14) = 1;
    REG(TIM3 + 0x10) = 0;
    REG(TIM3 + 0x0C) = 2;                            /* CC1IE */
    NVIC_ISER(0) = 1u << 29;
    tim3_cc_irqs = 0;
    REG(TIM3) = 1;
    delay_cycles(800000);                            /* 50 ms */
    REG(TIM3 + 0x0C) = 0;
    uint32_t n = tim3_cc_irqs;
    result("compare interrupt", n >= 49 && n <= 51, "matches in 50 ms:", n);
}

static void test_pwm_pin(void)
{
    /* TIM3 still runs at 1 kHz; CH1 on PC6 (AF2), PWM mode 1, 25 % */
    AHB2ENR |= 4u;
    pin_mode(GPIOC, 6, 2, 2);
    REG(TIM3 + 0x18) = 0x68;                         /* OC1M = PWM1, OC1PE */
    REG(TIM3 + 0x20) = 1;                            /* CC1E */
    delay_cycles(160000);
    result("PWM on PC6", ask("pwm PC6 1000 250") == 'y', "board disagrees", 0);
    REG(TIM3 + 0x20) = 3;                            /* CC1P: inverted */
    delay_cycles(160000);
    result("PWM polarity", ask("pwm PC6 1000 750") == 'y', "board disagrees", 0);
    REG(TIM3) = 0;
}

static void test_one_pulse(void)
{
    APB1ENR1 |= 4u;                                  /* TIM4 */
    REG(TIM4 + 0x28) = 15;
    REG(TIM4 + 0x2C) = 999;
    REG(TIM4 + 0x14) = 1;
    REG(TIM4 + 0x10) = 0;
    REG(TIM4) = 1u | (1u << 3);                      /* CEN, OPM */
    delay_cycles(8000);                              /* 0.5 ms: still counting */
    int mid = (REG(TIM4) & 1u) && !(REG(TIM4 + 0x10) & 1u);
    delay_cycles(16000);                             /* 1.5 ms: stopped at the update */
    int end = !(REG(TIM4) & 1u) && (REG(TIM4 + 0x10) & 1u);
    result("one-pulse mode", mid && end, "mid/end:", (uint32_t)(mid * 10 + end));
}

static void test_advanced_moe(void)
{
    /* TIM1 CH1 on PA8 (AF6), centre-aligned, 16 kHz, 25 %: no output until MOE */
    APB2ENR |= 1u << 11;
    AHB2ENR |= 1u;
    pin_mode(GPIOA, 8, 2, 6);
    REG(TIM1 + 0x28) = 0;
    REG(TIM1 + 0x2C) = 500;
    REG(TIM1 + 0x34) = 125;
    REG(TIM1 + 0x18) = 0x68;
    REG(TIM1 + 0x20) = 1;
    REG(TIM1 + 0x14) = 1;
    REG(TIM1) = 1u | (1u << 5);                      /* CEN, CMS = centre-aligned 1 */
    delay_cycles(16000);
    result("TIM1 output off without MOE", ask("floating PA8") == 'y', "board disagrees", 0);
    REG(TIM1 + 0x44) |= 1u << 15;                    /* MOE */
    delay_cycles(16000);
    result("TIM1 centre-aligned PWM", ask("pwm PA8 16000 250") == 'y', "board disagrees", 0);
    REG(TIM1) = 0;
}

static void test_exti(void)
{
    AHB2ENR |= 4u;
    APB2ENR |= 1u;                                   /* SYSCFG */
    pin_mode(GPIOC, 13, 0, 0);
    REG(GPIOC + 0x0C) = (REG(GPIOC + 0x0C) & ~(3u << 26)) | (1u << 26);   /* pull-up */
    REG(SYSCFG + 0x14) = (REG(SYSCFG + 0x14) & ~0xF0u) | 0x20u;          /* EXTI13 = port C */
    REG(EXTI + 0x0C) |= 1u << 13;                    /* falling */
    REG(EXTI + 0x00) |= 1u << 13;                    /* unmasked */
    NVIC_ISER(1) = 1u << (40 - 32);
    exti_irqs = 0;
    ask("button PC13 0");
    ask("press 1");
    delay_cycles(160000);
    ask("release 1");
    delay_cycles(160000);
    result("EXTI interrupt, clean button", exti_irqs == 1, "interrupts:", exti_irqs);
    exti_irqs = 0;
    ask("button PC13 3000");
    ask("press 1");
    delay_cycles(160000);
    ask("release 1");
    delay_cycles(160000);
    result("EXTI interrupt, bouncing button", exti_irqs >= 2, "interrupts:", exti_irqs);
    exti_irqs = 0;
    REG(EXTI + 0x10) = 1u << 13;                     /* software interrupt */
    delay_cycles(100);
    result("EXTI software interrupt", exti_irqs == 1, "interrupts:", exti_irqs);
}

static void adc_on(uint32_t adc)
{
    REG(adc + 0x08) &= ~(1u << 29);                  /* leave deep power-down */
    REG(adc + 0x08) |= 1u << 28;                     /* regulator */
    delay_cycles(400);                               /* 25 us */
    REG(adc + 0x08) |= 1u << 31;                     /* calibrate */
    while (REG(adc + 0x08) & (1u << 31)) {}
    REG(adc + 0x08) |= 1u;
    while (!(REG(adc) & 1u)) {}
}

static uint32_t adc_convert(uint32_t adc, int ch, uint32_t smp, uint32_t *cycles)
{
    REG(adc + 0x30) = (uint32_t)ch << 6;
    REG(adc + 0x14) = smp << (3 * (ch % 10));
    REG(adc + 0x18) = smp << (3 * (ch % 10));
    uint32_t t0 = CYCCNT;
    REG(adc + 0x08) |= 1u << 2;
    while (!(REG(adc) & (1u << 2))) {}
    *cycles = CYCCNT - t0;
    return REG(adc + 0x40);
}

static void test_adc(void)
{
    AHB2ENR |= 1u << 13;
    REG(ADC1 + 0x08) |= 1u << 28;                    /* regulator while still in deep power-down: */
    ADC12_CCR = 1u << 16;                            /* clock HCLK/1 */
    adc_on(ADC1);
    adc_on(ADC2);
    pin_mode(GPIOC, 0, 3, 0);
    ask("pot PC0 250");
    uint32_t cyc, v = adc_convert(ADC1, 6, 0, &cyc);
    result("ADC reads the pot", v >= 1022 && v <= 1026, "code:", v);
    result("ADC conversion time, 2.5 + 12.5 cycles", cyc >= 15 && cyc <= 45, "CPU cycles:", cyc);
    v = adc_convert(ADC1, 6, 7, &cyc);
    result("ADC conversion time, 640.5 + 12.5 cycles", cyc >= 653 && cyc <= 690, "CPU cycles:", cyc);
    /* DAC1 OUT1 on PA4 read back by ADC2 channel 17 */
    AHB2ENR |= (1u << 16) | 1u;
    pin_mode(GPIOA, 4, 3, 0);
    REG(DAC1) |= 1u;
    REG(DAC1 + 0x08) = 3000;
    v = adc_convert(ADC2, 17, 2, &cyc);
    result("DAC to ADC loopback", v >= 2995 && v <= 3005, "code:", v);
    result("DAC on the board", ask("mv PA4 2418") == 'y', "board disagrees", 0);
}

static void test_input_capture(void)
{
    /* TIM2 CH1 on PA0 (AF1) captures the press of a button to 3V3 */
    AHB2ENR |= 1u;
    pin_mode(GPIOA, 0, 2, 1);
    REG(GPIOA + 0x0C) = (REG(GPIOA + 0x0C) & ~3u) | 2u;   /* pull-down */
    ask("button PA0 vcc");
    REG(TIM2 + 0x0C) = 0;
    REG(TIM2 + 0x2C) = 0xFFFFFFFFu;
    REG(TIM2 + 0x28) = 15;
    REG(TIM2 + 0x14) = 1;
    REG(TIM2 + 0x18) = 1;                            /* CC1S = TI1 */
    REG(TIM2 + 0x20) = 1;                            /* CC1E, rising */
    REG(TIM2 + 0x10) = 0;
    REG(TIM2) = 1;
    delay_cycles(16000 * 5);                         /* 5 ms */
    ask("press 2");
    int flag = (REG(TIM2 + 0x10) >> 1) & 1u;
    uint32_t cap = REG(TIM2 + 0x34);
    result("input capture", flag && cap >= 5000 && cap <= 7000, "captured at us:", cap);
    REG(TIM2) = 0;
}

int main(void)
{
    DEMCR |= 1u << 24;
    DWT_CTRL |= 1u;
    AHB2ENR |= 1u;
    APB1ENR1 |= 1u << 17;
    pin_mode(GPIOA, 2, 2, 7);
    pin_mode(GPIOA, 3, 2, 7);
    REG(USART2 + 0x0C) = 139;                        /* 16 MHz / 115200 */
    REG(USART2) = 1u | (1u << 2) | (1u << 3);
    puts_("periph tests\n");
    test_timer_rate();
    test_compare_irq();
    test_pwm_pin();
    test_one_pulse();
    test_advanced_moe();
    test_exti();
    test_adc();
    test_input_capture();
    puts_(fails ? "done, failures: " : "done, all passed ");
    putu((uint32_t)fails);
    puts_("\n");
    wait_tx_done();
    for (;;) __asm__ volatile ("wfi");
}

/* ---- start-up ---- */
extern uint32_t _sbss, _ebss, _estack;
void reset_handler(void)
{
    for (uint32_t *p = &_sbss; p < &_ebss; p++) *p = 0;
    main();
}
static void default_handler(void) { for (;;) {} }

__attribute__((section(".vectors"), used))
static void (*const vectors[16 + 102])(void) = {
    [0] = (void (*)(void))&_estack,
    [1] = reset_handler,
    [2 ... 15] = default_handler,
    [16 ... 117] = default_handler,
    [16 + 28] = tim2_handler,
    [16 + 29] = tim3_handler,
    [16 + 40] = exti15_10_handler,
};
