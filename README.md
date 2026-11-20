# Solderless

An Android app for learning how the STM32G431 works. It emulates an
STM32G431RB, register for register, and runs the unmodified
[Mecrisp-Stellaris](https://mecrisp.sourceforge.net/) Forth for the G431
or firmware you build yourself. A virtual breadboard with buttons, LEDs,
sensors, displays and more follows in the next milestones (see
[docs/design.md](docs/design.md)).

**Status:** milestone 2, the breadboard (on the `milestone-2` branch):

- the Board tab: the chip's pins as two headers along the screen's long
  edges (in LQFP64-to-DIP adapter order or by port), parts in between with
  live wires, glowing LEDs, the pot's voltage, the piezo's frequency
- buttons (with contact bounce), LEDs, RGB LEDs, potentiometers, piezos
  and speakers; the app picks the pins and the resistors, shows why, and
  writes `wiring.fs` with a constant for every pin
- EXTI, the timers with PWM and input capture, ADC and DAC; sound from the
  piezo and the speaker
- parts can float over the Console and Editor while you type

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

Every push builds a debug APK on GitHub Actions. Open the latest run under
**Actions → CI**, download the `solderless-debug` artifact, unzip it and
install the APK (you may need to allow installing from unknown sources).

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
