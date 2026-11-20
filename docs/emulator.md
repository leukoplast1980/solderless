# What the emulator models

Status for milestone 2. "Works like the chip" means: same registers, reset
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
  from the breadboard (below).
- **USART1-3, UART4, LPUART1**: transmit and receive with frame timing,
  baud rate from the kernel clock, word length, parity and stop bits; TXE,
  TC, RXNE, overrun (with OVRDIS), framing and parity errors, interrupts.
  A byte reaches the terminal only if the USART's TX is routed to PA2 by
  the GPIO alternate function, and arrives from PA3 the same way. With
  different baud rates on the two sides, the receiver samples the other
  side's waveform at its own rate and sees what a real UART would see.
  Not yet: FIFO mode, idle line detection, LIN, IrDA, smartcard, flow
  control, auto baud rate.
- **SYSCFG, EXTI** (lines 0-15): port selection in EXTICR, rising and
  falling triggers, interrupt mask, pending flags, software interrupt,
  event mask (wakes WFE), the shared interrupts of lines 5-9 and 10-15.
- **Timers TIM1-4, 6-8, 15-17**: prescaler, auto-reload with preload, up,
  down and centre-aligned counting, update events (UDIS, URS, UG),
  repetition counter, one-pulse mode, output compare and PWM modes with
  polarity and complementary outputs, MOE on the advanced timers, input
  capture of pin edges, interrupts (TIM1 and TIM8 on their four lines,
  shared with TIM15-17). TIM2 is 32 bits wide. The counter is worked out
  from the time when read, so a running timer costs nothing between
  accesses. Not yet: slave modes and triggers, encoder mode (milestone 3),
  dead time, break inputs, DMA.
- **ADC1, ADC2**: deep power-down, the voltage regulator and its 20 us
  start-up, calibration, enable and ADRDY, the clock source (ADC12_CCR
  CKMODE or RCC_CCIPR, which selects no clock after reset), regular
  sequences of up to 16 channels, single and continuous mode, resolution,
  alignment, sampling times and the resulting conversion time, EOSMP,
  EOC, EOS and overrun, interrupts, the temperature sensor, VREFINT and
  VBAT/3. Not yet: injected conversions, external triggers,
  oversampling, analog watchdogs, offsets, differential inputs, DMA.
- **DAC1**: outputs 1 and 2 on PA4 and PA5 in analog mode, all data
  register alignments, software trigger, the output buffer. Not yet:
  timer triggers, wave generation, DMA, sample and hold.
- **I2C1-3** as masters: START with the 7-bit address from CR2, the
  part's acknowledge or NACKF with the automatic STOP, TXIS and TXE,
  RXNE (the last byte read is not acknowledged), NBYTES with TC, RELOAD
  with TCR, AUTOEND, repeated START, STOP, the clock stretched while TXDR
  is empty or RXDR full, interrupts, PE as the software reset. Bytes go
  out whole on the virtual clock, nine SCL periods each, at the rate
  TIMINGR gives with the kernel clock chosen in CCIPR. As on the chip, the
  pins must be in the I2C alternate function and open-drain, and the lines
  need pull-ups: without them SCL stays low and START waits with BUSY set.
  Not yet: slave mode, 10-bit addresses, SMBus, timeouts, DMA.
- **SPI1-3** as masters: the 32-bit transmit and receive FIFOs with the
  packing rules of DR (an 8-bit access moves one byte, a wider one two, so
  a word written with `!` in 8-bit mode sends two frames), data sizes of 4
  to 16 bits, FRXTH (with its reset value RXNE waits for two bytes), TXE,
  RXNE, BSY and the FIFO levels, overrun, the mode fault when the master
  sees NSS low (SSM and SSI, or the NSS pin), NSS driven by the SPI with
  SSOE, CPOL and CPHA, LSBFIRST, SCK from PCLK and BR, interrupts. Frames
  reach the parts whole, at the end of their time on the wire, so a chip
  select raised before BSY clears cuts the last byte off, as on the bench.
  Not yet: slave mode, bidirectional and receive-only modes, CRC, TI mode,
  NSS pulses, I2S, DMA.
- RTC, DMA, comparators, op-amps and the rest: registers only, until their
  milestones.

## The breadboard

Each chip pin is a net. Every driver on it is a voltage behind a
resistance: the chip's output stage (about 40 ohm), its pull-ups and
pull-downs (about 40 kohm), a closed button, a potentiometer's wiper, an
external pull resistor, the DAC. The net settles at their weighted mean.

- A digital input reads high above 2.31 V and low below 0.99 V. In
  between, or with no driver at all (floating), it reads at random, and
  the console says so when the firmware reads it.
- Two strong drivers at opposite levels are a short.
- A pin driven by a timer channel in PWM mode carries a frequency and a
  duty cycle instead of single edges.
- LEDs take their brightness from the average current over each screen
  refresh; above 20 mA the console warns.
- Buttons bounce: a press or release chatters a few times over the bounce
  time (2 ms by default) before it settles, as real contacts do.
- Piezos and speakers listen to their pin: the app plays PWM as a square
  wave and edges and DAC samples as they come, about 80 ms behind.
- Bus parts sit on the pins of a bus and answer at their address. A
  module's pull-ups (4.7 kohm each on the common OLED boards) are drivers
  on SCL and SDA like any other, so two modules on one bus pull harder.
  The on-chip I2C reaches the parts in whole bytes. Firmware that toggles
  the pins itself (bit-banged I2C, common in Forth) works too: a decoder
  on each part watches the nets, takes a START as SDA falling while SCL is
  high and a bit as SDA when SCL rises, and the part answers by holding
  SDA low on the net, so the firmware reads its acknowledge and its data
  through IDR.
- **SSD1306 OLED** (128x64, I2C at 0x3C or 0x3D): the command set with
  its arguments, also when a driver sends a command and its arguments in
  separate transfers; the control byte (Co, D/C); horizontal, vertical
  and page addressing with the column and page windows; segment remap,
  COM scan direction and the COM pin configuration; multiplex ratio,
  display offset and start line; contrast, inverse, entire display on.
  After power-up its RAM holds noise, and it stays dark until the
  firmware turns on the charge pump (8D 14) and the display (AF), as the
  real module does. Reads return the status byte (D6 set while the
  display is off). Not yet: scrolling (the commands are accepted and the
  close-up says it is on, but the picture stands still).
- SPI parts listen while their chip select is low. Firmware that toggles
  the pins itself works too: the part takes MOSI on the rising edge of SCK
  and moves its output on the falling edge, driving MISO on the net only
  while it has something to say. A master in mode 1 or 2, too fast a clock
  or LSB first garbles the bits, and the console says which.
- **W25Q32/64/128 flash**: JEDEC, manufacturer, device and unique IDs;
  read and fast read; write enable, which clears itself after every
  program, erase and status write; page program that only turns 1s into
  0s and wraps at the end of its 256-byte page; 4 KB, 32 KB, 64 KB and
  chip erase; status registers 1-3 with the block protect bits; power-down
  and reset. Programs and erases take the datasheet's typical times with
  BUSY set, and the chip ignores everything but the status reads
  meanwhile. WP and HOLD are tied high. The contents are kept with the
  project and can be read and edited in a hex view. Not yet: dual and quad
  modes, suspend, security registers, SFDP.
- **MAX7219 LED matrix**, a single 8 x 8 module or a chain of four: one long
  shift register through the chain, latched when CS (LOAD) rises, so the
  first word sent reaches the chip farthest from DIN; digits, Code B
  decode, intensity, scan limit, shutdown, display test. At power-up the
  chips are in shutdown, scan one row and hold noise. Digit 0 is the top
  row, D7 the left column; the chip at DIN is on the right.
- **ST7789 colour display**, 240 x 240 or 240 x 320, with DC and RES: the 240 x
  320 frame memory, of which a 240-row panel shows rows 0-239; hardware and
  software reset, sleep, display on and off, inversion (these IPS panels
  show true colours with it on), 12-, 16- and 18-bit pixels (18 after
  reset), MADCTL, the address window, memory write. Commands sent before
  the datasheet's wait after a reset or Sleep Out are ignored. Not yet:
  reads, partial mode, scrolling.

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
- a floating input or one between the thresholds, read by the firmware
- a short between the chip and a part; more than 20 mA through an LED
- an advanced timer running with an output enabled but MOE clear
- SPI: a mode fault, no pin in SCK or MOSI, a mode, clock or bit order the
  part does not take, two parts answering on MISO at once, an overrun the
  firmware then reads
- the parts: a flash command while busy, a program or erase without write
  enable or into a protected block, programming over bytes that were not
  erased; the matrix's 5 V logic and a CS that rises after an odd number of
  bytes; a display command too soon after a reset, pixels written while it
  sleeps or is off, in 18-bit colour, or into rows the panel does not show
- I2C: no pull-ups on SCL or SDA, pins that are push-pull or not in the
  I2C alternate function, SCL faster than the part allows (it does not
  answer), two parts at one address, an address that was not shifted into
  bits 7:1 (CR2.SADD, or the first byte of a bit-banged transfer)
- the ADC without a clock, started before its regulator is ready,
  calibrated while enabled, started before ADRDY; an ADC channel read on a
  pin that is not in analog mode; an ADC overrun
