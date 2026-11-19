# Solderless

An Android app for learning how the STM32G431 works. It emulates an
STM32G431RB and the parts wired to it on a virtual breadboard: buttons, LEDs,
a potentiometer, a rotary encoder, a speaker, a pressure sensor, a CO2 sensor,
displays, an LED matrix and external flash. The parts behave like the real
chips and modules, at their pins and in their protocols.

It runs the unmodified [Mecrisp-Stellaris](https://mecrisp.sourceforge.net/)
Forth for the G431, and firmware you build yourself.

**Status:** design. See [docs/design.md](docs/design.md).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
