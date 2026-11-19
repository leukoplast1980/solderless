/* Test firmware layout: flash at 0x0800_0000, SRAM1 at 0x2000_0000 */
MEMORY
{
  FLASH (rx)  : ORIGIN = 0x08000000, LENGTH = 128K
  RAM   (rwx) : ORIGIN = 0x20000000, LENGTH = 16K
}
_estack = ORIGIN(RAM) + LENGTH(RAM);
SECTIONS
{
  .text : { KEEP(*(.vectors)) *(.text*) *(.rodata*) } > FLASH
  .bss (NOLOAD) : { _sbss = .; *(.bss*) *(COMMON) . = ALIGN(4); _ebss = .; } > RAM
  /DISCARD/ : { *(.data*) *(.ARM.*) *(.comment) }
}
