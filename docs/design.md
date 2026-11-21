# Solderless: design

Status: milestone 1 implemented, 19 November 2026. What the emulator
models today is in [emulator.md](emulator.md).

Solderless is an Android app for learning how the STM32G431 works. It
emulates an STM32G431RB and the parts wired to it on a virtual breadboard. It
runs the unmodified Mecrisp-Stellaris build for the G431, and it also runs
firmware you build yourself.

Everything here aims at one rule: **what works in Solderless works on a real
G431RB on a real breadboard, and what fails on the bench fails here too.**

## 1. Scope

In scope:

- The STM32G431RB (LQFP64) with its Cortex-M4F core and the on-chip
  peripherals in section 3.3.
- A virtual breadboard: parts, wires, passives, and the electrical rules that
  matter for logic-level circuits.
- Parts that behave like the real chips and modules, at their pins and in
  their protocols (section 4).
- A pin recommender that wires new parts to free pins, or proposes a re-wire.
- Views that show what the chip is doing: registers, clocks, pins, a log.
- Export of the wiring and the parts list, so the same build works on a real
  breadboard.

Out of scope:

- FDCAN, USB, SAI, UCPD.
- Cycle accuracy. Timing follows the datasheets, not the bus clock.
- Analog detail below the logic level: signal integrity, rise times, supply
  noise, ESD.
- ST's system bootloader ROM. The chip always boots from main flash.

## 2. The app

A separate app with its own repository. It copies the editor, console,
line loader and Forth syntax colouring from Mecrisp Pocket instead of sharing a
library. The two apps can share code later if that pays off.

Tabs: **Breadboard**, **Console**, **Editor**, **Registers**, **Pins**, **Log**.

A **project** holds everything for one build: the firmware image, the internal
flash contents, the breadboard (parts, wiring, settings), the contents of any
external flash chips, and the source files. Projects are saved and opened as a
whole.

License: GPL-3.0-or-later, like Mecrisp Pocket and Mecrisp-Stellaris.
Minimum Android version: 8.0, as for Mecrisp Pocket. Aim for F-Droid.

## 3. The emulated chip

### 3.1 Core

- ARMv7E-M: Thumb and Thumb-2 instructions, in our own C interpreter.
- Full exception model: NVIC with priorities and grouping, SysTick, PendSV,
  SVCall, fault handlers, exception stacking and EXC_RETURN, MSP and PSP,
  BASEPRI and FAULTMASK.
- Fault status registers (CFSR, HFSR, MMFAR, BFAR) set as on the real core, so
  the fault explainer (section 8) can decode them.
- The DSP instructions and the single-precision FPU with lazy stacking
  (milestone 3), so C firmware built for hard float runs; its arithmetic
  is done in integers to the ARM ARM's pseudocode, not on the phone's FPU.
- Milestone 5 adds the MPU.

Why our own interpreter: it fits the way Mecrisp Pocket's `rv32.c` already
does single-step, snapshots and virtual time, it is easy to hook for the
access log, and it has no outside dependency. Speed is enough: a good
interpreter on a current phone lands near the real chip's 170 MHz.

The run loop keeps its common case short: the rare conditions (a pending
interrupt, a fault, an IT block, an exception return, sleep) are one test
before and one after each instruction, and loads and stores that hit RAM or
flash are handled inline. The emulator is built optimised in debug APKs
too, which would otherwise run it about three times slower.

### 3.2 Memory

- 128 KB flash at `0x0800_0000`, also visible at `0x0000_0000` when booting from
  main flash. 2 KB pages, 64-bit programming.
- 32 KB SRAM: 22 KB SRAM1 and SRAM2 at `0x2000_0000`, and 10 KB CCM SRAM at
  `0x1000_0000`, which also appears at `0x2000_5800`. Together they form one
  continuous 32 KB block, which the Stellaris port relies on.
- Peripheral addresses and register layouts come from ST's SVD file for the
  G431 (see open question 1); this document does not repeat them.
- Device ID, unique ID and flash size registers return values like a real
  G431RB.

### 3.3 On-chip peripherals

Every register exists with its real reset value and read/write behaviour
(read-only bits, write-1-to-clear flags, read-to-clear sequences). Behaviour
is added where firmware can observe it.

| Peripheral | Milestone | Notes |
| --- | --- | --- |
| RCC, PWR | 1 | Computed clock tree. Ready bits follow the enable bits after a short delay. Whether an HSE or LSE crystal is fitted is a breadboard setting. |
| FLASH | 1 | Unlock keys, 64-bit programming, page and mass erase, all error flags for wrong sequences, wait states, option bytes read back. |
| GPIO (all ports of the RB) | 1 | Mode, output type, speed, pulls, alternate functions, BSRR/BRR, lock. Pins are driven onto the breadboard's nets. |
| USART, UART, LPUART (all) | 1 | USART2 is the Stellaris console. Baud rate is checked against the console's speed. |
| EXTI, SYSCFG | 2 | Including the shared EXTI line per pin number. |
| Timers (TIM1–4, 6–8, 15–17) | 2 | Counting, update events, compare and PWM, one-pulse, basic input capture. Encoder mode in milestone 3. |
| ADC | 2 | Conversions from the breadboard's analog nets. Sampling time and resolution honoured. |
| DAC | 2 | Output onto a net; to the speaker as audio. |
| I2C, SPI (all) | 3 | Transactions reach the parts through the bus layer (section 4.4). |
| DMA, DMAMUX | 5 | |
| RTC, TAMP, LPTIM, IWDG, WWDG, CRC, RNG | 5 | |
| CORDIC, FMAC | 5 | Pure math, exact results. |
| COMP, OPAMP, VREFBUF | 5 | Functional models from net voltages. |

A peripheral whose clock is off in RCC behaves as on silicon (writes do
nothing, reads return 0) and the Log says why.

### 3.4 Time

- **Virtual clock.** One instruction counts as one cycle at the current
  SYSCLK. All peripheral and part timing (timer ticks, UART byte times, sensor
  conversion times, flash busy times) runs on this clock.
- **Real-time pacing.** The clock is paced to the wall clock, so a one-second
  delay takes a second: LEDs blink at the right rate, sounds have the right
  pitch, the CO2 sensor delivers a value every 5 s. If the phone cannot keep
  up, emulated time runs slower and a speed indicator shows the ratio.
- **Batches.** The CPU runs at most 20,000 instructions at a time (about
  0.1 ms at full speed). The app's reads of the board, the displays and the
  scope wait for the running batch to end, so they stay short.
- **Deterministic mode** for tests: no pacing, no wall clock, same result on
  every run.
- **Idle detection.** Stellaris waits for a key by polling USART2's status
  register in a loop (`serial-key?` in `terminal.s`), not with WFI. When the CPU
  keeps reading the same status register with the same value and nothing else
  changes, the emulator sleeps until the next scheduled event or until input
  arrives. WFI and WFE do the same. This keeps the phone cool at the prompt and
  changes no observable behaviour.

### 3.5 Firmware

- Bundled: Mecrisp-Stellaris 3.0.2 `stm32g431kb-ra` (the G431 target arrived
  in 2.6.5), unmodified, version recorded in `firmware/stellaris/UPSTREAM.md`. It
  runs on the HSI16 clock with its console on USART2, PA2/PA3, 115200 baud. It
  runs unchanged on the G431RB, as on real chips.
- Your own firmware: `.bin` (loaded at `0x0800_0000`), `.hex`, `.elf` (load
  segments; symbols kept for the views).
- The internal flash is saved with the project, so `compiletoflash` words
  survive a restart, as on a real chip.
- Console: the line loader from Mecrisp Pocket, sending a line and waiting for
  Stellaris' ` ok.`, like e4thcom.
- Autocompletion reads Stellaris' dictionary from memory: the chain starts
  at the entry stored at `0x2000_7FD4` (newest RAM word, or the core's
  first word at `0xC4`), runs through the core into the flash dictionary at
  `0x5000` and ends at an erased link. These addresses belong to the bundled
  image and are kept in `firmware/stellaris/stellaris.json`.

## 4. The breadboard

### 4.1 Nets

The breadboard is a netlist: the chip's pins, each part's terminals, and the
passives, joined by nets. The model is logic-level with drive strengths, not a
circuit simulator:

- Each net resolves from its drivers: push-pull high or low, open-drain low,
  external or internal pull-up or pull-down (with resistance), or nothing.
- A net with no driver is **floating**: reads are unstable and the Log warns.
- Two push-pull drivers fighting is a **short**: the Log warns, the pins are
  marked.
- Analog nets carry a voltage (pot wiper, DAC output, a sensor's analog pin)
  for the ADC and comparators.
- A pin that toggles is recorded as timed edges. A timer channel in PWM mode
  puts a frequency and duty cycle on its net instead of single edges, so
  fast PWM costs nothing. LEDs take their brightness from the average.

### 4.2 Parts

| Part | Real model | Interface | Pins as on the real part or module |
| --- | --- | --- | --- |
| Push button | 6 mm tactile | GPIO input | 2 terminals |
| LED | 3/5 mm, red, green, yellow, blue, white | GPIO or PWM | anode, cathode |
| RGB LED | 5 mm, common cathode or common anode | 3 × PWM | R, G, B, common |
| Potentiometer | 10 kΩ linear | ADC | 2 ends, wiper |
| Rotary encoder | KY-040-style module, or a bare encoder | timer encoder mode or GPIO | CLK, DT, SW, +, GND |
| Speaker | small 8 Ω speaker with a transistor stage | DAC or PWM | 2 terminals |
| Piezo buzzer | passive piezo | PWM | 2 terminals |
| Pressure sensor | BMP280 module | I2C or SPI | VCC, GND, SCL, SDA, CSB, SDO |
| CO2 sensor | SCD41 module | I2C | VDD, GND, SCL, SDA |
| OLED display | SSD1306 128×64, I2C module or SPI module | I2C or SPI | I2C: GND, VCC, SCL, SDA. SPI: GND, VCC, D0, D1, RES, DC, CS |
| Colour display | ST7789 240×240 (240×320 variant) | SPI | GND, VCC, SCL, SDA, RES, DC, CS (if fitted), BLK |
| LED matrix | MAX7219 8×8 module, chainable (e.g. 4-in-1) | SPI-style | VCC, GND, DIN, CS, CLK, DOUT |
| External flash | W25Q32 / W25Q64 / W25Q128 module | SPI | VCC, GND, CS, DO, WP, DI, CLK, HOLD |

Parts are described in `data/parts/*.json` (pins, interface needs, passives
rules, module variants) and modelled in `emu/parts/*.c`.

### 4.3 Rules every part follows

1. **Same pins** as the real part or common module, with the same names.
2. **Strap pins work.** Address and mode pins are read as on the real part:
   BMP280's SDO selects address 0x76 or 0x77, and CSB pulled low at power-up
   switches it to SPI until the next power cycle. W25Q's WP and HOLD act on the
   chip.
3. **The datasheet's command and register set**, including ID registers, reset
   values, and documented behaviour on errors: a wrong I2C address is not
   acknowledged, commands sent while busy are ignored or rejected as the
   datasheet says.
4. **Datasheet timing** on the virtual clock: conversion times, measurement
   intervals, busy times, required waits after reset or wake-up. Not
   cycle-exact, but a driver that skips a required wait fails here as on the
   bench.
5. **Bus settings must match.** SPI mode, bit order and maximum clock, I2C
   speed: a mismatch garbles data the way the real bus would, and the Log says
   what was expected.
6. **Known gotchas stay in.** For example, the SSD1306 stays dark until the
   firmware enables its charge pump.
7. **Power matters.** A part without supply does nothing; a 5 V part on 3.3 V
   logic gets a warning (section 7).

### 4.4 How firmware reaches the parts

Part models talk in bus events: I2C start, address, byte with ACK/NACK, stop;
SPI chip-select edges and bytes in a given mode. Two sources produce them:

- **On-chip I2C and SPI peripherals**, directly, without simulating every bit.
  Their settings are checked against the part (rule 5).
- **Pin-level decoders** that watch the nets, so bit-banged drivers work.
  These are common in Forth. Here the bus mode is whatever the firmware
  actually produces, and a wrong mode garbles data naturally.

Several parts can share a bus: I2C parts by address (address clashes are
reported), SPI parts by their own chip-select pins.

### 4.5 Part details

- **Button**: hold to press. Contact bounce is on by default (adjustable,
  can be switched off), because real buttons bounce.
- **Encoder**: swipe to turn; quadrature with detents and bounce; tap for the
  push switch.
- **Potentiometer**: drag the knob; the wiper voltage goes to the ADC.
- **LED, RGB LED**: brightness from the average current; RGB shows the mixed
  colour.
- **Speaker, piezo**: a tone from PWM, or samples written to the DAC,
  played through the phone in real time.
- **BMP280**: chip ID, soft reset, calibration data, sleep, forced and normal
  modes, standby time, IIR filter, oversampling and the measurement time it
  costs, status bits, burst reads, 4-wire and 3-wire SPI. The model holds
  realistic calibration constants and produces raw readings that decode, with
  Bosch's published compensation formulas, to the pressure and temperature set
  in the app. Pressure can follow the phone's barometer, if it has one.
- **SCD41**: the datasheet command set, with the CRC on every word; periodic,
  low-power periodic and single-shot measurement with their intervals; data
  ready status; serial number; settings that persist only after the persist
  command; self-test time. CO2, temperature and humidity come from sliders;
  a "breathe" button raises CO2 and lets it settle back.
- **SSD1306**: the full command set (addressing modes, segment and COM remap,
  multiplex ratio, offset, contrast, charge pump, inverse, on/off; scrolling
  later). I2C with control bytes, SPI with the DC pin, the RES pin. Over
  I2C only the status byte reads back; no RAM reads, as on the real chip.
  Drawn pixel for pixel.
- **ST7789**: reset, sleep out with its wait, colour mode (16- and 18-bit),
  memory access control (rotation, RGB order), address window, memory write,
  inversion, display on. Backlight pin, dimmable by PWM.
- **MAX7219**: 16-bit frames latched on the rising edge of CS; decode mode,
  intensity, scan limit, shutdown, display test; chained modules through DOUT;
  the orientation of common modules.
- **W25Q**: JEDEC, manufacturer and unique ID; read and fast read; write
  enable, which clears itself after every operation; 256-byte page program
  that wraps at the end of the page; 4, 32 and 64 KB erase and chip erase;
  programming that only turns 1s into 0s; status registers with BUSY, WEL and
  the block protect bits; WP and HOLD; power-down; datasheet busy times. The
  contents are kept in a file with the project, with a hex view.

### 4.6 On screen

Settled with the mock-up (Solderless – Breadboard tab mock-up, 19 November
2026):

- **No chip drawn.** The chip's pins run as two pin headers down the long
  edges of the Board tab, all 64 with name and pin number; the parts sit in
  the space between, with short straight wires from their pins.
- **Header order is a setting**: *adapter order* (pins 1–32 down the left,
  64–33 down the right, as on an LQFP64-to-DIP adapter, so the screen
  matches a real chip on a breadboard) or *by port* (PA, PB on the left;
  PC, PD, PF, NRST and the supply pins on the right). Adapter order is the
  default. Tapping a pin lists its functions.
- **Live state.** Wires show their level: high, low, PWM, analog with its
  voltage, and checks in amber on the wire they concern (a floating input,
  say). LEDs glow with their average brightness, the RGB LED shows the
  mixed colour, the piezo its frequency.
- **Use and settings.** Tap a part to use it (hold the button, drag the
  pot); long-press for its settings sheet (colour, size, current, pin,
  active level, remove). Resistor values come with their calculation.
- **Sample code.** Tapping a part that does not react to touch (LED, RGB
  LED, piezo, speaker) or a button's label opens its sample code; every
  settings sheet has a Sample code button. A sample runs as pasted: the
  part's `wiring.fs` constants, a SysTick `ms`, an `-init` word that sets
  up the clocks, pins and peripheral, words to use the part (`led-on`,
  `rgb-colour`, `button?`, `button-pressed?` on its EXTI line, `pot@`,
  `piezo-tone`), and a demo that runs until a key is pressed. The code
  follows the part's settings and its pin's function (GPIO, timer
  channel, ADC, DAC). Every variant runs in the host tests on the real
  firmware (`tests/stellaris/samples_*.fs`).
- **Adding a part** opens a sheet with the parts; the recommender's choice
  is drawn dashed on the board with the reason, the resistor, the other
  valid pins (scarce or taken ones marked) and the sample code. A
  pin that is taken leads to the re-wire sheet with the moves and the
  `wiring.fs` diff.
- **Support circuit** (decoupling, VDDA filter, NRST capacitor, BOOT0
  pull-down, VBAT) sits behind a button, with the wiring and parts list
  exports.
- **Pinned parts.** A part can be pinned so it floats over the other tabs
  (Console, Editor, Scope, Registers): an LED, a button you can press, a
  display at its real pixel size. You see the circuit react while you type,
  trace or step. A pot is a pill filled as far as it is turned: drag along
  it to turn it, tap it for a full-width slider with - / + for 1 % steps.
  The card and the slider stay above the Console's input line.
- **Scope tab** (added in milestone 2, styled after Mecrisp Pocket's GPIO
  trace): one row per pin with a part, or added from its sheet; logic
  levels, voltages for analog pins, PWM as a square wave or a band, fast
  toggling as a dense mark; windows of 10 µs to 1 s, pinch (2 µs to 6 s)
  and drag, Hold, trigger on a rising or falling edge, cursors A and B.
  The emulator records each watched pin's changes with their time; the
  app keeps the last 6 seconds. From milestone 3 it decodes the buses:
  the pins of a bus are shown once, labelled by signal and peripheral,
  with the bits an on-chip I2C or SPI sent drawn on them; a row of boxes
  follows (S, "3C W ✓", "AF ✓", P; SPI bytes with what came back on MISO)
  and a line saying what the part made of the transfer at cursor A or the
  last one shown ("OLED: command AF, display on", "MAX7219: register 0C
  shutdown ← 01, normal operation").
- **Settings:** spread the pins when parts need room (the tab grows and
  scrolls), header order.
- **Displays** (milestone 3) are drawn as their modules with the screen
  content pixel for pixel; tapping one opens a close-up with a pixel grid
  and the controller's state (on/off, contrast, charge pump, addressing,
  orientation, last write), and saves it as PNG.

## 5. Pin recommender

Adding a part asks the recommender for pins. You accept the proposal or tap a
pin to pick another valid one.

**Data.** `data/g431rb-pins.json` lists every package pin: name, alternate
functions AF0–AF15, analog functions (ADC channels, DAC, COMP, OPAMP), 5 V
tolerance, and notes. One file feeds both the emulator (as a generated C
header) and the app.

**Needs.** Each part states what its pins need, for example:

- any GPIO output; a GPIO input with its own EXTI line
- a timer channel for PWM; three channels of one timer for an RGB LED
- channels 1 and 2 of one timer for encoder mode
- an ADC channel; a DAC output
- SCL and SDA of the same I2C; SCK, MOSI (and MISO) of the same SPI, plus
  GPIOs for CS, DC, RES

**Rules.**

- Reserved: PA2/PA3 (Stellaris console), PA13/PA14 (SWD, so a real chip stays
  flashable), NRST, and the crystal pins when a crystal is fitted.
- Caution: PB8 is also BOOT0 at reset; a part that could pull it high at
  reset is warned about.
- No two interrupt inputs on the same pin number, because they share one EXTI
  line (PA0 and PB0, for example).
- New I2C parts join an existing bus when their address is free; new SPI
  parts join an existing SPI with their own CS.
- Keep scarce pins free: DAC outputs, timer channels that can do encoder mode,
  ADC-capable pins are used only when needed.

**Search.** The problem is small (one chip, a handful of parts), so a
backtracking search with a score finds the best assignment. If nothing fits,
it searches again with existing parts allowed to move and proposes the
smallest re-wire as a diff:

> move LED from PA6 to PB4 to free TIM3 CH1 for the encoder

**Code stays valid.** The app generates `wiring.fs` with constants for every
pin and bus (`led-pin`, `oled-i2c`, …). A re-wire changes this one include, not
your code. The Log warns when the firmware sets up a pin differently from the
wiring, for example an LED pin left as an input.

## 6. Passives

Placed automatically, shown in the wires, each with a one-line reason. Values
are calculated and rounded to E12.

- **LED**: series resistor from colour (forward voltage) and a target current,
  for example a red LED at about 5 mA gets 270 Ω. RGB LEDs get one per colour.
- **Buttons, encoder**: the chip's internal pull-up by default, or a 10 kΩ
  external pull-up (setting). With internal pull-ups the app shows the GPIO
  setting the firmware must make. Optional RC debounce.
- **I2C**: one pull-up pair per bus, sized for the bus speed. Modules that
  already carry pull-ups show them; when several modules' pull-ups in parallel
  get too strong, the app says so and suggests removing some.
- **SPI**: none, except tying W25Q's WP and HOLD high.
- **Speaker**: NPN transistor, base resistor, flyback diode. A piezo connects
  directly.
- **Displays, sensors, flash**: a decoupling capacitor per module, unless the
  module has one.
- **The chip itself**: decoupling on each VDD pin, VDDA filtering, the NRST
  capacitor, a BOOT0 pull-down. Shown once, as the chip's support circuit.

## 7. Checks and warnings

Shown in the Log and marked on the breadboard and in the Registers view:

- a peripheral used while its clock is off in RCC
- a baud rate divider that no longer matches after a clock change
- too few flash wait states for SYSCLK
- flash written without unlocking, or over data that was not erased
- floating inputs, shorts, I2C address clashes
- bus settings that do not match a part (rule 5)
- a pin set up differently from its wiring
- current per pin and in total above the datasheet limits
- 5 V parts on 3.3 V logic (MAX7219 modules, for example), and 5 V signals on
  pins that are not 5 V tolerant

## 8. Learning views

- **Registers**: every peripheral register with its fields named and
  described, from the SVD. Changes are highlighted; fields can be edited
  while paused.
- **Access log**: which instruction changed which field, from what to what.
  Filter by peripheral.
- **Clock tree**: live diagram from RCC, from the oscillators through the PLL
  to each peripheral's clock.
- **Pins**: each pin's mode, alternate function, pull and live level.
- **Fault explainer**: on a fault, the fault registers decoded into plain
  words, with the faulting instruction and the stacked registers.
- **Run control**: pause, single step, reset, as in Mecrisp Pocket.

## 9. Export

- Wiring list: chip pin → part pin, with the passives.
- Parts list (BOM) with values.
- `wiring.fs`.

So the same build can go onto a real breadboard with a real G431RB, and the
same Forth runs on both.

## 10. Code layout and threads

```
app/                  Android app (Kotlin)
emu/                  emulator library (C), also built for the host
  core/armv7m.c       CPU
  soc/g431.c          memory map, bus, clock tree, event scheduler
  soc/periph/         one file per on-chip peripheral
  board/net.c         nets and the electrical model
  board/bus.c         I2C/SPI bus events and the pin-level decoders
  parts/              one file per part model
  cli/                host runner for tests
data/
  g431rb-pins.json    pin table
  parts/              part descriptions
tools/                generators: pin table → C header, SVD → register tables
firmware/stellaris/   bundled Mecrisp-Stellaris image and UPSTREAM.md
tests/
docs/
```

- One VM thread runs the core, the peripherals, the nets and the parts, as in
  Mecrisp Pocket.
- The app owns the breadboard layout, the recommender and the passives, and
  hands the resulting netlist to the emulator.
- The UI reads snapshots (pins, LEDs, display memory, registers) at screen
  rate. Touches, sliders and sensor values go to the VM thread as timed
  events.

## 11. Testing

- **CPU**: a host test runner with instruction tests, like `tests/riscv` in
  Mecrisp Pocket.
- **Against real chips**: the same Forth test scripts run on a real G431RB over
  serial and in the emulator; their output and register dumps are compared.
- **Parts**: known drivers (Forth drivers from the Mecrisp community, and
  vendor C drivers where the license allows) run against the part models.
  Where real modules are at hand, the same scripts run against them.
- **CI**: host tests and a debug APK on every push.

## 12. Milestones

1. **Chip and console.** CPU (integer), memory, RCC, PWR, FLASH, GPIO, USART,
   SysTick and NVIC. Stellaris reaches ` ok.`. Console, editor, line loader,
   firmware loading, projects.
2. **Breadboard.** Nets, simple parts (button, LED, RGB LED, potentiometer,
   speaker, piezo), recommender, passives, `wiring.fs`, export. Needs EXTI,
   timers, ADC, DAC. Pinned parts.
3. **Bus parts.** I2C, SPI, the pin-level decoders, BMP280, SCD41, SSD1306,
   ST7789, MAX7219, W25Q, encoder mode. FPU and DSP instructions.
4. **Learning views.** Registers from the SVD, access log, clock tree, pins,
   fault explainer, the checks in section 7.
5. **The rest of the chip.** DMA, RTC, watchdogs, CRC, RNG, LPTIM, CORDIC, FMAC,
   comparators, op-amps, VREFBUF, MPU.

Each milestone ends with a release that is useful on its own.

## 13. Open questions

1. ~~**SVD and pin data licensing.**~~ Settled: ST's SVD is Apache-2.0 and
   the stm32-data chip file is MIT OR Apache-2.0; both are bundled in
   `data/` with their licences.
2. ~~**Breadboard look.**~~ Settled with the mock-up (section 4.6).
3. ~~**Stellaris dictionary reader.**~~ Settled: read from memory (section 3.5).
4. ~~**Idle detection limits.**~~ Settled in milestone 1: register poll
   loops and busy loops whose state repeats are skipped to the next event;
   loops that write to peripherals never are (emulator.md, Time).
5. **Debugger.** A GDB stub for C firmware: after milestone 5, or earlier if
   C firmware becomes a main use.
