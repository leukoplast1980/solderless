# Chip data

Solderless generates its register tables and pin tables from two sources.
`tools/gen_g431.py` turns them into `emu/gen/g431_data.c`.

| File | What | Origin | License |
| --- | --- | --- | --- |
| `svd/STM32G431xx.svd` | Peripheral registers: addresses, reset values, fields | STMicroelectronics, via [cmsis-svd-data](https://github.com/cmsis-svd/cmsis-svd-data) | Apache-2.0 (header of the file, `svd/LICENSE-APACHE.md`) |
| `chip/STM32G431RB.json` | Peripherals, RCC enable bits, interrupts, package pins, alternate functions | [stm32-data](https://github.com/embassy-rs/stm32-data) (generated from ST's STM32CubeMX database) | MIT OR Apache-2.0 (`chip/LICENSE-*`) |

ST's SVD has a few wrong reset values; `tools/gen_g431.py` corrects them
(`RESET_FIX`) with values from the reference manual RM0440.
