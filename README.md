# Solderless

An Android app for learning how the STM32G431 works. It emulates an
STM32G431RB, register for register, and runs the unmodified
[Mecrisp-Stellaris](https://mecrisp.sourceforge.net/) Forth for the G431
or firmware you build yourself, with a virtual breadboard of buttons,
LEDs, potentiometers, sounders, displays, sensors, an SPI flash and a
rotary encoder. Views for learning the registers, the clock tree and
faults follow in the next milestones (see [docs/design.md](docs/design.md)).

<p>
  <a href="https://github.com/user-attachments/assets/1a55c2fd-3d18-4490-b4b9-a4ee30c0245f"><img width="150" alt="Screenshot_20261120-164003" src="https://github.com/user-attachments/assets/1a55c2fd-3d18-4490-b4b9-a4ee30c0245f" /></a>
  <a href="https://github.com/user-attachments/assets/2d306a71-29ee-4ce9-b573-9246e969b5d0"><img width="150" alt="Screenshot_20261120-164041" src="https://github.com/user-attachments/assets/2d306a71-29ee-4ce9-b573-9246e969b5d0" /></a>
  <a href="https://github.com/user-attachments/assets/1c904441-ddc0-4885-901c-cf9148db11bf"><img width="150" alt="Screenshot_20261120-164046" src="https://github.com/user-attachments/assets/1c904441-ddc0-4885-901c-cf9148db11bf" /></a>
  <a href="https://github.com/user-attachments/assets/8f2710f4-0bf3-4988-a547-6174b9b86e20"><img width="150" alt="Screenshot_20261120-164107" src="https://github.com/user-attachments/assets/8f2710f4-0bf3-4988-a547-6174b9b86e20" /></a>
  <a href="https://github.com/user-attachments/assets/7fd1d107-c9e8-4004-ad89-2df79d6b94e8"><img width="150" alt="Screenshot_20261120-164113" src="https://github.com/user-attachments/assets/7fd1d107-c9e8-4004-ad89-2df79d6b94e8" /></a>
</p>

**Status:** milestone 3, the bus parts:

- on I2C: the SSD1306 OLED, the BMP280 pressure sensor (or the phone's
  barometer) and the SCD41 CO2 sensor; on SPI: the W25Q flash with a hex
  view, the MAX7219 LED matrix and the ST7789 colour display
- the KY-040 rotary encoder on a timer in encoder mode: swipe to turn it
- each with a close-up or sheet showing what its controller holds, and
  sample code that runs as pasted
- the Scope decodes I2C and SPI, from the on-chip peripherals and from
  bit-banged pins, and says what the part made of each transfer
- the Cortex-M4's DSP instructions and its single-precision FPU

Milestone 2, the breadboard:

- the Board tab: the chip's pins as two headers along the screen's long
  edges (in LQFP64-to-DIP adapter order or by port), parts in between with
  live wires, glowing LEDs, the pot's voltage, the piezo's frequency
- buttons (with contact bounce), LEDs, RGB LEDs, potentiometers, piezos
  and speakers; the app picks the pins and the resistors, shows why, and
  writes `wiring.fs` with a constant for every pin
- ready-to-run Forth for every part: tap it for code that sets up its
  clocks, pins and peripheral and drives or reads it
- EXTI, the timers with PWM and input capture, ADC and DAC; sound from the
  piezo and the speaker
- parts can float over the Console and Editor while you type
- the Scope tab: every part's pin over time, logic levels, voltages and
  PWM, Hold, trigger on an edge, two cursors

Milestone 1, the chip and the console:

- the Cortex-M4 core (integer instructions, exceptions, NVIC, SysTick)
  and the G431's RCC, PWR, flash controller, GPIO and serial ports, with
  every other register present at its reset value
  ([docs/emulator.md](docs/emulator.md) lists what is emulated)
- Mecrisp-Stellaris 3.0.2 in flash, talking over USART2 on PA2/PA3 to the
  app's console; words compiled to flash are kept
- console, editor with completion and syntax colouring, Registers view
  with Pause and Step, projects, firmware loading (.bin, .hex, .elf)
- messages from the chip model where a real chip stays silent: a
  peripheral used with its clock off, too few flash wait states, a wrong
  baud rate, a flash write without unlocking, and more

## Trying it

Each release on the **Releases** page has a signed APK. Every push also
builds a debug APK on GitHub Actions: open the latest run under
**Actions → CI**, download the `solderless-debug` artifact and unzip it.
Either way you may need to allow installing from unknown sources.

The first start opens `first-steps.fs`, a tour of the chip from Forth:
the chip ID, the clocks, SysTick and a GPIO pin. Send it with
Send → Whole file. The Mecrisp-Stellaris libraries `lib_pll` (170 MHz),
`lib_systick` and `lib_rtc` are under Files.

- **Reset** is the chip's reset button; a long press powers it off and on
  (RAM cleared)
- ⋮ → **Projects…**: each project is a board with its own flash
- ⋮ → **Load firmware…** runs your own firmware; the serial terminal stays
  on PA2/PA3 at 115200 baud
- ⋮ → **Restore Mecrisp-Stellaris…** puts a fresh Forth back

## Building

Android: `./gradlew assembleDebug` (JDK 17, Android SDK, NDK
27.0.12077973).

The emulator also builds for the host, with a command-line runner and the
tests:

```sh
tests/run_host_tests.sh            # builds into build/host and runs all tests
build/host/slemu firmware/stellaris/mecrisp-stellaris-stm32g431kb.bin   # interactive
build/host/slemu -d -s file.fs firmware/stellaris/mecrisp-stellaris-stm32g431kb.bin
```

`-d` runs without real-time pacing and the same way every time: typing
takes no time, and while the firmware waits for input, time stands still.

The tests need `arm-none-eabi-as` for the exception tests and the Python
module `unicorn` for comparing the instruction set against Unicorn.

## Credits

- Mecrisp-Stellaris by Matthias Koch, G431 port by Ralph Sahli, maintained
  by Robert Clausecker (GPL-3.0-or-later); see
  [firmware/stellaris/UPSTREAM.md](firmware/stellaris/UPSTREAM.md)
- Register descriptions from STMicroelectronics' SVD (Apache-2.0) and pin
  data from [stm32-data](https://github.com/embassy-rs/stm32-data)
  (MIT OR Apache-2.0); see [data/README.md](data/README.md)
- The editor, console and completion come from
  [Mecrisp Pocket](https://github.com/leukoplast1980/mecrisp-pocket)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
