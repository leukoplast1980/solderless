\ The Registers view's reads and writes, as a debugger makes them (dpeek,
\ dpoke): reads without side effects, writes with them
#!dpeek 48000000 4002104C E000E010
\ PA5 an output, driven high, by poking MODER and BSRR
#!dpoke 48000000 EBFFF7AF
#!dpoke 48000018 00000020
$48000000 @ hex.
$48000014 @ hex.
#!dpeek 48000014
\ A poke into SysTick (the core's System Control Space) and into RAM
#!dpoke E000E014 00001234
$E000E014 @ hex.
#!dpoke 20007000 CAFEF00D
$20007000 @ hex.
\ A peripheral with its clock off: the write is lost, as on the chip
#!dpoke 40013808 00001234
#!dpeek 40013808
\ Nothing there: a bus error
#!dpoke 60000000 1
