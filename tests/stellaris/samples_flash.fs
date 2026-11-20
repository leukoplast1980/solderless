\ The sample code the Board tab gives for SPI flash, as generated
\ (SamplesTest checks that it still matches): a W25Q64 on SPI1, a W25Q32 on SPI2.
#!attach 1 flash PA4 PA5 PA7 PA6
#!attach 2 flash PB12 PB13 PB15 PB14 size=32

\ flash: W25Q64 flash, 8 MB, on SPI1: CS PA4, CLK PA5, DI PA7, DO PA6
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PA4 (pin 18) → W25Q64 CS; WP and HOLD → 3V3, VCC → 3V3, GND → GND, 100 nF from VCC to GND
$48000000 constant flash-cs-port  \ GPIOA
4 constant flash-cs-pin
\ PA5 (pin 19) → W25Q64 CLK
$48000000 constant flash-clk-port  \ GPIOA
5 constant flash-clk-pin
$40013000 constant flash-spi  \ SPI1
5 constant flash-clk-af
\ PA7 (pin 21) → W25Q64 DI (data into the flash)
$48000000 constant flash-di-port  \ GPIOA
7 constant flash-di-pin
5 constant flash-di-af
\ PA6 (pin 20) ← W25Q64 DO (data out of the flash)
$48000000 constant flash-do-port  \ GPIOA
6 constant flash-do-pin
5 constant flash-do-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: flash-sr ( -- u ) flash-spi 8 + @ ;             \ SPI1 SR
: >flash> ( b -- b )                              \ one byte out, one byte in
  begin flash-sr 2 and until  flash-spi $C + c!   \ wait for TXE; c! writes one frame
  begin flash-sr 1 and until  flash-spi $C + c@ ; \ wait for RXNE: the byte that came back
: flash-on ( c -- )                               \ CS low, then the command byte
  1 flash-cs-pin lshift 16 lshift flash-cs-port $18 + !  >flash> drop ;
: flash-off ( -- ) 1 flash-cs-pin lshift flash-cs-port $18 + ! ; \ CS high: the command ends here
: >flash-addr ( a -- )                            \ 24-bit address, high byte first
  dup 16 rshift >flash> drop  dup 8 rshift >flash> drop  >flash> drop ;
: flash-status ( -- u ) 5 flash-on 0 >flash> flash-off ; \ status register 1: BUSY bit 0, WEL bit 1
: flash-wait ( -- ) begin flash-status 1 and 0= until ; \ until a program or erase is done
: flash-wren ( -- ) 6 flash-on flash-off ;        \ write enable, before every program and erase
: flash-id ( -- )                                 \ JEDEC ID: EF Winbond, 40, then the size
  $9F flash-on  0 >flash> hex. 0 >flash> hex. 0 >flash> hex.  flash-off ;
: flash-read ( a addr u -- )                      \ u bytes from the flash at a to memory at addr
  rot 3 flash-on >flash-addr
  0 ?do 0 >flash> over i + c! loop drop flash-off ;
: flash-write ( addr u a -- )                     \ u bytes to the flash at a, within one 256-byte page
  flash-wren 2 flash-on >flash-addr
  0 ?do dup i + c@ >flash> drop loop drop flash-off flash-wait ; \ it programs when CS goes high
: flash-erase ( a -- )                            \ the 4 KB sector around a, back to FF
  flash-wren $20 flash-on >flash-addr flash-off flash-wait ; \ takes about 45 ms

: flash-init ( -- )
  1 0 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOA
  1 12 lshift $40021060 bis!                      \ RCC APB2ENR: clock for SPI1
  flash-off                                       \ CS high before it becomes an output
  %11 flash-cs-pin 2* lshift flash-cs-port bic!   \ MODER: clear the two bits
  %01 flash-cs-pin 2* lshift flash-cs-port bis!   \ MODER: 01 = output
  1 flash-cs-pin lshift flash-cs-port 4 + bic!    \ OTYPER: push-pull
  %11 flash-clk-pin 2* lshift flash-clk-port bic! \ MODER: clear the two bits
  %10 flash-clk-pin 2* lshift flash-clk-port bis! \ MODER: 10 = alternate function
  1 flash-clk-pin lshift flash-clk-port 4 + bic!  \ OTYPER: push-pull
  $F flash-clk-pin 4 * lshift flash-clk-port $20 + bic! \ AFRL: clear the four bits
  flash-clk-af flash-clk-pin 4 * lshift flash-clk-port $20 + bis! \ AFRL: AF5 = SPI1 SCK
  %11 flash-di-pin 2* lshift flash-di-port bic!   \ MODER: clear the two bits
  %10 flash-di-pin 2* lshift flash-di-port bis!   \ MODER: 10 = alternate function
  1 flash-di-pin lshift flash-di-port 4 + bic!    \ OTYPER: push-pull
  $F flash-di-pin 4 * lshift flash-di-port $20 + bic! \ AFRL: clear the four bits
  flash-di-af flash-di-pin 4 * lshift flash-di-port $20 + bis! \ AFRL: AF5 = SPI1 MOSI
  %11 flash-do-pin 2* lshift flash-do-port bic!   \ MODER: clear the two bits
  %10 flash-do-pin 2* lshift flash-do-port bis!   \ MODER: 10 = alternate function
  1 flash-do-pin lshift flash-do-port 4 + bic!    \ OTYPER: push-pull
  $F flash-do-pin 4 * lshift flash-do-port $20 + bic! \ AFRL: clear the four bits
  flash-do-af flash-do-pin 4 * lshift flash-do-port $20 + bis! \ AFRL: AF5 = SPI1 MISO
  $1700 flash-spi 4 + !                           \ CR2: 8-bit frames, RXNE after one byte (FRXTH)
  $354 flash-spi ! ;                              \ CR1: master, SCK = 16 MHz / 8, SSM and SSI (CS is a GPIO), on

\ Erase a sector, write a line, read it back
: flash-hello s" Hello from Forth" ;
64 buffer: flash-buf
: flash-demo ( -- )
  flash-init flash-id cr
  0 flash-erase  flash-hello 0 flash-write
  0 flash-buf 16 flash-read  flash-buf 16 type ;

\ Try it: flash-init flash-id   or   flash-demo

\ flash2: W25Q32 flash, 4 MB, on SPI2: CS PB12, CLK PB13, DI PB15, DO PB14
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB12 (pin 34) → W25Q32 CS; WP and HOLD → 3V3, VCC → 3V3, GND → GND, 100 nF from VCC to GND
$48000400 constant flash2-cs-port  \ GPIOB
12 constant flash2-cs-pin
\ PB13 (pin 35) → W25Q32 CLK
$48000400 constant flash2-clk-port  \ GPIOB
13 constant flash2-clk-pin
$40003800 constant flash2-spi  \ SPI2
5 constant flash2-clk-af
\ PB15 (pin 37) → W25Q32 DI (data into the flash)
$48000400 constant flash2-di-port  \ GPIOB
15 constant flash2-di-pin
5 constant flash2-di-af
\ PB14 (pin 36) ← W25Q32 DO (data out of the flash)
$48000400 constant flash2-do-port  \ GPIOB
14 constant flash2-do-pin
5 constant flash2-do-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: flash2-sr ( -- u ) flash2-spi 8 + @ ;           \ SPI2 SR
: >flash2> ( b -- b )                             \ one byte out, one byte in
  begin flash2-sr 2 and until  flash2-spi $C + c! \ wait for TXE; c! writes one frame
  begin flash2-sr 1 and until  flash2-spi $C + c@ ; \ wait for RXNE: the byte that came back
: flash2-on ( c -- )                              \ CS low, then the command byte
  1 flash2-cs-pin lshift 16 lshift flash2-cs-port $18 + !  >flash2> drop ;
: flash2-off ( -- ) 1 flash2-cs-pin lshift flash2-cs-port $18 + ! ; \ CS high: the command ends here
: >flash2-addr ( a -- )                           \ 24-bit address, high byte first
  dup 16 rshift >flash2> drop  dup 8 rshift >flash2> drop  >flash2> drop ;
: flash2-status ( -- u ) 5 flash2-on 0 >flash2> flash2-off ; \ status register 1: BUSY bit 0, WEL bit 1
: flash2-wait ( -- ) begin flash2-status 1 and 0= until ; \ until a program or erase is done
: flash2-wren ( -- ) 6 flash2-on flash2-off ;     \ write enable, before every program and erase
: flash2-id ( -- )                                \ JEDEC ID: EF Winbond, 40, then the size
  $9F flash2-on  0 >flash2> hex. 0 >flash2> hex. 0 >flash2> hex.  flash2-off ;
: flash2-read ( a addr u -- )                     \ u bytes from the flash at a to memory at addr
  rot 3 flash2-on >flash2-addr
  0 ?do 0 >flash2> over i + c! loop drop flash2-off ;
: flash2-write ( addr u a -- )                    \ u bytes to the flash at a, within one 256-byte page
  flash2-wren 2 flash2-on >flash2-addr
  0 ?do dup i + c@ >flash2> drop loop drop flash2-off flash2-wait ; \ it programs when CS goes high
: flash2-erase ( a -- )                           \ the 4 KB sector around a, back to FF
  flash2-wren $20 flash2-on >flash2-addr flash2-off flash2-wait ; \ takes about 45 ms

: flash2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 14 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for SPI2
  flash2-off                                      \ CS high before it becomes an output
  %11 flash2-cs-pin 2* lshift flash2-cs-port bic! \ MODER: clear the two bits
  %01 flash2-cs-pin 2* lshift flash2-cs-port bis! \ MODER: 01 = output
  1 flash2-cs-pin lshift flash2-cs-port 4 + bic!  \ OTYPER: push-pull
  %11 flash2-clk-pin 2* lshift flash2-clk-port bic! \ MODER: clear the two bits
  %10 flash2-clk-pin 2* lshift flash2-clk-port bis! \ MODER: 10 = alternate function
  1 flash2-clk-pin lshift flash2-clk-port 4 + bic! \ OTYPER: push-pull
  $F flash2-clk-pin 8 - 4 * lshift flash2-clk-port $24 + bic! \ AFRH: clear the four bits
  flash2-clk-af flash2-clk-pin 8 - 4 * lshift flash2-clk-port $24 + bis! \ AFRH: AF5 = SPI2 SCK
  %11 flash2-di-pin 2* lshift flash2-di-port bic! \ MODER: clear the two bits
  %10 flash2-di-pin 2* lshift flash2-di-port bis! \ MODER: 10 = alternate function
  1 flash2-di-pin lshift flash2-di-port 4 + bic!  \ OTYPER: push-pull
  $F flash2-di-pin 8 - 4 * lshift flash2-di-port $24 + bic! \ AFRH: clear the four bits
  flash2-di-af flash2-di-pin 8 - 4 * lshift flash2-di-port $24 + bis! \ AFRH: AF5 = SPI2 MOSI
  %11 flash2-do-pin 2* lshift flash2-do-port bic! \ MODER: clear the two bits
  %10 flash2-do-pin 2* lshift flash2-do-port bis! \ MODER: 10 = alternate function
  1 flash2-do-pin lshift flash2-do-port 4 + bic!  \ OTYPER: push-pull
  $F flash2-do-pin 8 - 4 * lshift flash2-do-port $24 + bic! \ AFRH: clear the four bits
  flash2-do-af flash2-do-pin 8 - 4 * lshift flash2-do-port $24 + bis! \ AFRH: AF5 = SPI2 MISO
  $1700 flash2-spi 4 + !                          \ CR2: 8-bit frames, RXNE after one byte (FRXTH)
  $354 flash2-spi ! ;                             \ CR1: master, SCK = 16 MHz / 8, SSM and SSI (CS is a GPIO), on

\ Erase a sector, write a line, read it back
: flash2-hello s" Hello from Forth" ;
64 buffer: flash2-buf
: flash2-demo ( -- )
  flash2-init flash2-id cr
  0 flash2-erase  flash2-hello 0 flash2-write
  0 flash2-buf 16 flash2-read  flash2-buf 16 type ;

\ Try it: flash2-init flash2-id   or   flash2-demo

flash-demo
flash2-init flash2-id
flash2-hello 32 flash2-write  32 flash2-buf 16 flash2-read  flash2-buf 16 type
#!flash 1 0 16
#!flash 2 32 16
