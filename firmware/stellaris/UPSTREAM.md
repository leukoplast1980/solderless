# Mecrisp-Stellaris for the STM32G431

`mecrisp-stellaris-stm32g431kb.bin` and the `lib_*.txt` files are taken
unmodified from the official Mecrisp-Stellaris release:

- Release: Mecrisp-Stellaris 3.0.2 (18 April 2026), target `stm32g431kb-ra`
- Source: <https://sourceforge.net/projects/mecrisp/files/mecrisp-stellaris-3.0.2.tar.gz>
- SHA-256 of the tarball: `6d4effce03e4076fc92c3852f89e1656fe893b5fdb524149cf1698149bf0bd16`
- SHA-256 of the image: `fd6dd1b87ece91bf28c75d43011244025998c2285428b94701aaf9c6234bfc39`
- Authors: Matthias Koch; G431 port by Ralph Sahli; maintained by Robert Clausecker
- License: GPL-3.0-or-later (see the release's `COPYING`; the source is in the
  tarball under `mecrisp-stellaris-source/stm32g431kb-ra`)

The port was written for the NUCLEO-G431KB. It runs on the 16 MHz HSI
oscillator with its terminal on USART2, PA2 (TX) and PA3 (RX), at
115200 baud. It runs unchanged on the G431RB.

Known quirk of the port: its interrupt vector table (`vectors.s`) was taken
from the STM32G071 port, so the interrupt hooks sit at the G071's interrupt
numbers. On the G431, `irq-exti0_1` is at interrupt 5 (RCC), `irq-exti2_3`
at 6 (EXTI0), `irq-exti4_15` at 7 (EXTI1), and `irq-adc`, `irq-tim1_up`,
`irq-tim1_cc` and `irq-tim3` at 12, 13, 14 and 16 (DMA channels). The table
also ends after interrupt 32, while the G431 has interrupts up to 101:
enabling, for example, USART2's interrupt (38) makes the core fetch its
handler address from the code that follows the table. SysTick and the other
core exceptions are fine. The emulator behaves like the chip in all of this.
