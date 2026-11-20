# What the emulator models

Status for milestone 1. "Works like the chip" means: same registers, reset
values and documented behaviour as in RM0440 (the G4 reference manual) and
the ARMv7-M Architecture Reference Manual. Timing follows the datasheets,
not the clock cycle.

## Core

- Thumb and Thumb-2 integer instructions of the Cortex-M4, including the
  extend-and-add instructions. Checked against Unicorn on random
  instruction sequences (`tests/cpu/diff_test.py`).
- Exceptions: NVIC with 102 interrupts and 4 priority bits, priority
  grouping, preemption, tail chaining, PRIMASK, BASEPRI, FAULTMASK,
  Thread and Handler mode, MSP and PSP, unprivileged Thread mode,
  8-byte stack alignment, fault escalation to HardFault, lockup.
  Checked by the test firmware in `tests/cpu/exc_test.S`.
- Faults set CFSR, HFSR, BFAR and MMFAR like the chip: undefined
  instructions, Thumb state lost, bad EXC_RETURN, unaligned LDM/STM/LDRD,
  division by zero (with DIV_0_TRP), bus errors, execution from
  peripheral addresses, stacking errors.
- SysTick, the DWT cycle counter, bit-banding of SRAM and peripherals,
  the exclusive monitor, WFI/WFE/SEV.
- Not yet: the FPU and the DSP instructions (they raise a UsageFault, as
  on a core without them), the MPU (its registers exist but nothing is
  enforced), debug features.

## Memory

| Region | Address | |
| --- | --- | --- |
| Flash | `0x0800_0000`, 128 KiB, also at `0x0000_0000` | written only through the flash controller |
| SRAM1, SRAM2 | `0x2000_0000`, 22 KiB | |
| CCM SRAM | `0x1000_0000`, 10 KiB, also at `0x2000_5800` | |
| System memory | `0x1FFF_0000` | unique ID, flash size, calibration values; no bootloader |
| Peripherals | `0x4000_0000`… | every register of the G431's peripherals |

An access where there is neither memory nor a peripheral is a bus error.

## Peripherals

Every register of every G431 peripheral exists with its reset value and
its writable bits, taken from ST's SVD. Behaviour so far:

- **RCC**: HSI16, HSE (if the board has a crystal), LSI, LSE, HSI48 and the
  PLL become ready after their start-up time; the system clock switches
  when the new source is ready; AHB and APB prescalers; peripheral clock
  enables (a peripheral with its clock off reads 0 and ignores writes) and
  resets; kernel clock selection for the serial ports; backup domain
  write protection (PWR_CR1.DBP); reset flags.
- **PWR**: voltage range and boost mode, used for the checks below.
- **FLASH**: unlock keys (a wrong key locks the controller until reset),
  64-bit programming as two word writes, page and mass erase with the
  datasheet times and BSY, the error flags for wrong sequences, wait
  states, the end-of-operation and error interrupts. Option bytes read back
  at their factory values.
- **GPIO**: all registers, including the lock sequence. Pin levels come
  from the port's drivers, pulls and the board; for now the board is only
  the serial adapter on PA2/PA3.
- **USART1-3, UART4, LPUART1**: transmit and receive with frame timing,
  baud rate from the kernel clock, word length, parity and stop bits; TXE,
  TC, RXNE, overrun (with OVRDIS), framing and parity errors, interrupts.
  A byte reaches the terminal only if the USART's TX is routed to PA2 by
  the GPIO alternate function, and arrives from PA3 the same way. With
  different baud rates on the two sides, the receiver samples the other
  side's waveform at its own rate and sees what a real UART would see.
  Not yet: FIFO mode, idle line detection, LIN, IrDA, smartcard, flow
  control, auto baud rate.
- Timers, ADC, DAC, I2C, SPI, DMA, EXTI and the rest: registers only, until
  their milestones.

## Time

Emulated time is the cycle count at the current HCLK (one instruction per
cycle) and follows the wall clock. When the firmware waits, the emulator
sleeps until the next event instead of running the waiting loop:

- WFI and WFE
- a loop that keeps reading the same peripheral register with the same
  result (Mecrisp-Stellaris waiting for a key)
- a loop that comes round the same backward branch with the same
  registers and memory (a delay waiting for an interrupt to change a
  counter)

Such loops can only end at an event (a timer, a byte on the serial line,
a flash operation finishing, input), so skipping them changes nothing the
firmware can see. Loops that write to peripherals are never skipped.

## Messages from the chip

The console shows these in amber, at most three times each per run:

- a peripheral read or written while its clock is off in RCC
- flash wait states (FLASH_ACR.LATENCY) too low for HCLK
- SYSCLK above 150 MHz without range 1 boost mode
- PLL input, multiplier or VCO out of range; PLL without an input clock
- HSE or LSE switched on without a crystal on the board
- RCC_BDCR written while the backup domain is write-protected
- flash: write without PG, wrong key, wrong sequence, size or alignment,
  programming over data that was not erased
- the terminal and a USART using different baud rates or frame formats
- a USART overrun; typed characters arriving while the receiver is off
- bus errors: access where there is no memory or peripheral
