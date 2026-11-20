\ The sample code the Board tab gives for LED matrices, as generated
\ (SamplesTest checks that it still matches): a 4-in-1 on SPI3, an 8 x 8 on SPI1.
#!attach 1 matrix PC9 PC10 PC12 chips=4
#!attach 2 matrix PB10 PB3 PB5 chips=1

\ matrix: LED matrix, 4 × MAX7219, 32 × 8, on SPI3: CS PC9, CLK PC10, DIN PC12
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PC9 (pin 41) → MAX7219 CS (LOAD); VCC → 5 V, GND → GND
$48000800 constant matrix-cs-port  \ GPIOC
9 constant matrix-cs-pin
\ PC10 (pin 52) → MAX7219 CLK
$48000800 constant matrix-clk-port  \ GPIOC
10 constant matrix-clk-pin
$40003C00 constant matrix-spi  \ SPI3
6 constant matrix-clk-af
\ PC12 (pin 54) → MAX7219 DIN, on the module's input end
$48000800 constant matrix-din-port  \ GPIOC
12 constant matrix-din-pin
6 constant matrix-din-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: matrix-sr ( -- u ) matrix-spi 8 + @ ;           \ SPI3 SR
: >matrix ( b -- )                                \ one byte out
  begin matrix-sr 2 and until  matrix-spi $C + c! ; \ wait for TXE; c! writes one frame
: matrix-idle ( -- ) begin matrix-sr $80 and 0= until ; \ until BSY clears: the last byte is out
: matrix-select ( -- ) 1 matrix-cs-pin lshift 16 lshift matrix-cs-port $18 + ! ; \ CS (LOAD) low
: matrix-load ( -- ) matrix-idle 1 matrix-cs-pin lshift matrix-cs-port $18 + ! ; \ CS high: each chip takes its 16 bits
: matrix-all ( reg v -- )                         \ the same register in every chip
  matrix-select  4 0 do over >matrix dup >matrix loop 2drop matrix-load ;

32 buffer: matrix-buf                             \ the picture: 8 rows of 4 bytes, left to right
: matrix-clear ( -- ) matrix-buf 32 0 fill ;
: matrix-pixel ( x y -- )                         \ x 0..31 from the left, y 0..7 from the top
  over 32 u< over 8 u< and 0= if 2drop exit then
  4 * over 3 rshift + matrix-buf +                \ the byte: row y, column x/8
  $80 rot 7 and rshift swap cbis! ;               \ bit 7 is the left LED
: matrix-show ( -- )                              \ the picture to the LEDs, a row at a time
  8 0 do
    matrix-select
    4 0 do  j 1+ >matrix  j 4 * i + matrix-buf + c@ >matrix  loop \ register: row + 1; the left chip first
    matrix-load
  loop ;

: matrix-init ( -- )
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  1 15 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for SPI3
  1 matrix-cs-pin lshift matrix-cs-port $18 + !   \ BSRR: high before it becomes an output
  %11 matrix-cs-pin 2* lshift matrix-cs-port bic! \ MODER: clear the two bits
  %01 matrix-cs-pin 2* lshift matrix-cs-port bis! \ MODER: 01 = output
  1 matrix-cs-pin lshift matrix-cs-port 4 + bic!  \ OTYPER: push-pull
  %11 matrix-clk-pin 2* lshift matrix-clk-port bic! \ MODER: clear the two bits
  %10 matrix-clk-pin 2* lshift matrix-clk-port bis! \ MODER: 10 = alternate function
  1 matrix-clk-pin lshift matrix-clk-port 4 + bic! \ OTYPER: push-pull
  $F matrix-clk-pin 8 - 4 * lshift matrix-clk-port $24 + bic! \ AFRH: clear the four bits
  matrix-clk-af matrix-clk-pin 8 - 4 * lshift matrix-clk-port $24 + bis! \ AFRH: AF6 = SPI3 SCK
  %11 matrix-din-pin 2* lshift matrix-din-port bic! \ MODER: clear the two bits
  %10 matrix-din-pin 2* lshift matrix-din-port bis! \ MODER: 10 = alternate function
  1 matrix-din-pin lshift matrix-din-port 4 + bic! \ OTYPER: push-pull
  $F matrix-din-pin 8 - 4 * lshift matrix-din-port $24 + bic! \ AFRH: clear the four bits
  matrix-din-af matrix-din-pin 8 - 4 * lshift matrix-din-port $24 + bis! \ AFRH: AF6 = SPI3 MOSI
  $1700 matrix-spi 4 + !                          \ CR2: 8-bit frames
  $354 matrix-spi !                               \ CR1: master, SCK = 16 MHz / 8 (it takes 10 MHz), SSM and SSI, on
  $F 0 matrix-all                                 \ display test off
  9 0 matrix-all                                  \ no decoding: each bit is an LED
  $B 7 matrix-all                                 \ scan all 8 rows: after power-up only row 0 shows
  $A 3 matrix-all                                 \ intensity 3 of 15
  matrix-clear matrix-show                        \ its rows hold noise at power-up
  $C 1 matrix-all ;                               \ out of shutdown: on

\ A dot bouncing about until a key is pressed
0 variable matrix-x  0 variable matrix-y  1 variable matrix-dx  1 variable matrix-dy
: matrix-move ( -- )
  matrix-dx @ matrix-x +!  matrix-x @ 0= matrix-x @ 31 = or if matrix-dx @ negate matrix-dx ! then
  matrix-dy @ matrix-y +!  matrix-y @ 0= matrix-y @ 7 = or if matrix-dy @ negate matrix-dy ! then ;
: matrix-demo ( -- )
  matrix-init
  begin matrix-clear matrix-x @ matrix-y @ matrix-pixel matrix-show matrix-move 50 ms key? until ;

\ Try it: matrix-init 3 3 matrix-pixel matrix-show   or   matrix-demo (Enter stops it)

\ matrix2: LED matrix, MAX7219, 8 × 8, on SPI1: CS PB10, CLK PB3, DIN PB5
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB10 (pin 30) → MAX7219 CS (LOAD); VCC → 5 V, GND → GND
$48000400 constant matrix2-cs-port  \ GPIOB
10 constant matrix2-cs-pin
\ PB3 (pin 56) → MAX7219 CLK
$48000400 constant matrix2-clk-port  \ GPIOB
3 constant matrix2-clk-pin
$40013000 constant matrix2-spi  \ SPI1
5 constant matrix2-clk-af
\ PB5 (pin 58) → MAX7219 DIN, on the module's input end
$48000400 constant matrix2-din-port  \ GPIOB
5 constant matrix2-din-pin
5 constant matrix2-din-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: matrix2-sr ( -- u ) matrix2-spi 8 + @ ;         \ SPI1 SR
: >matrix2 ( b -- )                               \ one byte out
  begin matrix2-sr 2 and until  matrix2-spi $C + c! ; \ wait for TXE; c! writes one frame
: matrix2-idle ( -- ) begin matrix2-sr $80 and 0= until ; \ until BSY clears: the last byte is out
: matrix2-select ( -- ) 1 matrix2-cs-pin lshift 16 lshift matrix2-cs-port $18 + ! ; \ CS (LOAD) low
: matrix2-load ( -- ) matrix2-idle 1 matrix2-cs-pin lshift matrix2-cs-port $18 + ! ; \ CS high: each chip takes its 16 bits
: matrix2-all ( reg v -- )                        \ the same register in every chip
  matrix2-select  1 0 do over >matrix2 dup >matrix2 loop 2drop matrix2-load ;

8 buffer: matrix2-buf                             \ the picture: 8 rows of 1 bytes, left to right
: matrix2-clear ( -- ) matrix2-buf 8 0 fill ;
: matrix2-pixel ( x y -- )                        \ x 0..7 from the left, y 0..7 from the top
  over 8 u< over 8 u< and 0= if 2drop exit then
  1 * over 3 rshift + matrix2-buf +               \ the byte: row y, column x/8
  $80 rot 7 and rshift swap cbis! ;               \ bit 7 is the left LED
: matrix2-show ( -- )                             \ the picture to the LEDs, a row at a time
  8 0 do
    matrix2-select
    1 0 do  j 1+ >matrix2  j 1 * i + matrix2-buf + c@ >matrix2  loop \ register: row + 1; the left chip first
    matrix2-load
  loop ;

: matrix2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 12 lshift $40021060 bis!                      \ RCC APB2ENR: clock for SPI1
  1 matrix2-cs-pin lshift matrix2-cs-port $18 + ! \ BSRR: high before it becomes an output
  %11 matrix2-cs-pin 2* lshift matrix2-cs-port bic! \ MODER: clear the two bits
  %01 matrix2-cs-pin 2* lshift matrix2-cs-port bis! \ MODER: 01 = output
  1 matrix2-cs-pin lshift matrix2-cs-port 4 + bic! \ OTYPER: push-pull
  %11 matrix2-clk-pin 2* lshift matrix2-clk-port bic! \ MODER: clear the two bits
  %10 matrix2-clk-pin 2* lshift matrix2-clk-port bis! \ MODER: 10 = alternate function
  1 matrix2-clk-pin lshift matrix2-clk-port 4 + bic! \ OTYPER: push-pull
  $F matrix2-clk-pin 4 * lshift matrix2-clk-port $20 + bic! \ AFRL: clear the four bits
  matrix2-clk-af matrix2-clk-pin 4 * lshift matrix2-clk-port $20 + bis! \ AFRL: AF5 = SPI1 SCK
  %11 matrix2-din-pin 2* lshift matrix2-din-port bic! \ MODER: clear the two bits
  %10 matrix2-din-pin 2* lshift matrix2-din-port bis! \ MODER: 10 = alternate function
  1 matrix2-din-pin lshift matrix2-din-port 4 + bic! \ OTYPER: push-pull
  $F matrix2-din-pin 4 * lshift matrix2-din-port $20 + bic! \ AFRL: clear the four bits
  matrix2-din-af matrix2-din-pin 4 * lshift matrix2-din-port $20 + bis! \ AFRL: AF5 = SPI1 MOSI
  $1700 matrix2-spi 4 + !                         \ CR2: 8-bit frames
  $354 matrix2-spi !                              \ CR1: master, SCK = 16 MHz / 8 (it takes 10 MHz), SSM and SSI, on
  $F 0 matrix2-all                                \ display test off
  9 0 matrix2-all                                 \ no decoding: each bit is an LED
  $B 7 matrix2-all                                \ scan all 8 rows: after power-up only row 0 shows
  $A 3 matrix2-all                                \ intensity 3 of 15
  matrix2-clear matrix2-show                      \ its rows hold noise at power-up
  $C 1 matrix2-all ;                              \ out of shutdown: on

\ A dot bouncing about until a key is pressed
0 variable matrix2-x  0 variable matrix2-y  1 variable matrix2-dx  1 variable matrix2-dy
: matrix2-move ( -- )
  matrix2-dx @ matrix2-x +!  matrix2-x @ 0= matrix2-x @ 7 = or if matrix2-dx @ negate matrix2-dx ! then
  matrix2-dy @ matrix2-y +!  matrix2-y @ 0= matrix2-y @ 7 = or if matrix2-dy @ negate matrix2-dy ! then ;
: matrix2-demo ( -- )
  matrix2-init
  begin matrix2-clear matrix2-x @ matrix2-y @ matrix2-pixel matrix2-show matrix2-move 50 ms key? until ;

\ Try it: matrix2-init 3 3 matrix2-pixel matrix2-show   or   matrix2-demo (Enter stops it)

matrix-init 3 3 matrix-pixel 31 7 matrix-pixel matrix-show
#!matrix 1
matrix2-init 0 0 matrix2-pixel 7 7 matrix2-pixel matrix2-show
#!matrix 2
