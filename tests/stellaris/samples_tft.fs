\ The sample code the Board tab gives for colour displays, as generated
\ (SamplesTest checks that it still matches): 240 x 240 on SPI1, 240 x 320 on SPI2.
#!attach 1 tft PA4 PA5 PA7 PB0 PB1
#!attach 2 tft PB12 PB13 PB15 PC6 PC7 rows=320

\ tft: colour display, ST7789 240 × 240, on SPI1: CS PA4, SCL PA5, SDA PA7, DC PB0, RES PB1
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PA4 (pin 18) → display CS; VCC → 3V3, GND → GND, BLK → 3V3
$48000000 constant tft-cs-port  \ GPIOA
4 constant tft-cs-pin
\ PA5 (pin 19) → display SCL (the SPI clock, despite the name)
$48000000 constant tft-scl-port  \ GPIOA
5 constant tft-scl-pin
$40013000 constant tft-spi  \ SPI1
5 constant tft-scl-af
\ PA7 (pin 21) → display SDA (SPI data in, MOSI)
$48000000 constant tft-sda-port  \ GPIOA
7 constant tft-sda-pin
5 constant tft-sda-af
\ PB0 (pin 24) → display DC (low: command, high: data)
$48000400 constant tft-dc-port  \ GPIOB
0 constant tft-dc-pin
\ PB1 (pin 25) → display RES (low: reset)
$48000400 constant tft-res-port  \ GPIOB
1 constant tft-res-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: tft-sr ( -- u ) tft-spi 8 + @ ;                 \ SPI1 SR
: >tft ( b -- )                                   \ one byte out
  begin tft-sr 2 and until  tft-spi $C + c! ;     \ wait for TXE; c! writes one frame
: tft-idle ( -- ) begin tft-sr $80 and 0= until ; \ until BSY clears: the last byte is out
: tft-dc ( f -- ) tft-idle  1 tft-dc-pin lshift   \ DC changes only after the byte before it is out
  swap 0= if 16 lshift then tft-dc-port $18 + ! ; \ BSRR: high for data, low for a command
: tft-cmd ( c -- )                                \ CS low, the command with DC low; DC high for what follows
  1 tft-cs-pin lshift 16 lshift tft-cs-port $18 + !  0 tft-dc >tft  1 tft-dc ;
: tft-end ( -- ) tft-idle 1 tft-cs-pin lshift tft-cs-port $18 + ! ; \ CS high
: >tft16 ( u -- ) dup 8 rshift >tft >tft ;        \ 16 bits, high byte first
: tft-window ( x0 y0 x1 y1 -- )                   \ the pixels that follow fill this box, row by row
  >r >r  $2A tft-cmd swap >tft16 r> >tft16        \ CASET: columns
  $2B tft-cmd >tft16 r> >tft16  $2C tft-cmd ;     \ RASET: rows; RAMWR
: tft-fill ( x0 y0 x1 y1 colour -- )              \ a box in one RGB565 colour
  >r 2over 2over tft-window  rot - 1+ -rot swap - 1+ *
  r> swap 0 ?do dup >tft16 loop drop tft-end ;
: tft-pixel ( x y colour -- ) >r 2dup tft-window r> >tft16 tft-end ;
: tft-reset ( -- )                                \ a pulse on RES, then the 120 ms it needs
  1 tft-res-pin lshift 16 lshift tft-res-port $18 + !  1 ms
  1 tft-res-pin lshift tft-res-port $18 + !  120 ms ;

: tft-init ( -- )
  1 0 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOA
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 12 lshift $40021060 bis!                      \ RCC APB2ENR: clock for SPI1
  1 tft-cs-pin lshift tft-cs-port $18 + !         \ BSRR: high before it becomes an output
  %11 tft-cs-pin 2* lshift tft-cs-port bic!       \ MODER: clear the two bits
  %01 tft-cs-pin 2* lshift tft-cs-port bis!       \ MODER: 01 = output
  1 tft-cs-pin lshift tft-cs-port 4 + bic!        \ OTYPER: push-pull
  %11 tft-scl-pin 2* lshift tft-scl-port bic!     \ MODER: clear the two bits
  %10 tft-scl-pin 2* lshift tft-scl-port bis!     \ MODER: 10 = alternate function
  1 tft-scl-pin lshift tft-scl-port 4 + bic!      \ OTYPER: push-pull
  $F tft-scl-pin 4 * lshift tft-scl-port $20 + bic! \ AFRL: clear the four bits
  tft-scl-af tft-scl-pin 4 * lshift tft-scl-port $20 + bis! \ AFRL: AF5 = SPI1 SCK
  %11 tft-sda-pin 2* lshift tft-sda-port bic!     \ MODER: clear the two bits
  %10 tft-sda-pin 2* lshift tft-sda-port bis!     \ MODER: 10 = alternate function
  1 tft-sda-pin lshift tft-sda-port 4 + bic!      \ OTYPER: push-pull
  $F tft-sda-pin 4 * lshift tft-sda-port $20 + bic! \ AFRL: clear the four bits
  tft-sda-af tft-sda-pin 4 * lshift tft-sda-port $20 + bis! \ AFRL: AF5 = SPI1 MOSI
  1 tft-dc-pin lshift tft-dc-port $18 + !         \ BSRR: high before it becomes an output
  %11 tft-dc-pin 2* lshift tft-dc-port bic!       \ MODER: clear the two bits
  %01 tft-dc-pin 2* lshift tft-dc-port bis!       \ MODER: 01 = output
  1 tft-dc-pin lshift tft-dc-port 4 + bic!        \ OTYPER: push-pull
  1 tft-res-pin lshift tft-res-port $18 + !       \ BSRR: high before it becomes an output
  %11 tft-res-pin 2* lshift tft-res-port bic!     \ MODER: clear the two bits
  %01 tft-res-pin 2* lshift tft-res-port bis!     \ MODER: 01 = output
  1 tft-res-pin lshift tft-res-port 4 + bic!      \ OTYPER: push-pull
  $1700 tft-spi 4 + !                             \ CR2: 8-bit frames
  $344 tft-spi !                                  \ CR1: master, SCK = 16 MHz / 2, SSM and SSI, on
  tft-reset
  $11 tft-cmd tft-end 5 ms                        \ Sleep Out, then 5 ms before the next command
  $3A tft-cmd $55 >tft tft-end                    \ COLMOD: RGB565, 2 bytes a pixel (18-bit after reset)
  $36 tft-cmd 0 >tft tft-end                      \ MADCTL: row 0 at the top, column 0 on the left
  $21 tft-cmd tft-end                             \ inversion on: this IPS panel shows true colours so
  $29 tft-cmd tft-end ;                           \ Display On

\ Colour bars, and a box that changes colour until a key is pressed
create tft-colours  $FFFF h, $FFE0 h, $07FF h, $07E0 h, $F81F h, $F800 h, $001F h, 0 h,
: tft-colour ( n -- c ) 7 and 2* tft-colours + h@ ;
: tft-bars ( -- )
  8 0 do  i 30 * 0  i 30 * 29 + 239  i tft-colour tft-fill  loop ;
: tft-demo ( -- )
  tft-init tft-bars
  0 begin  70 70 169 169 4 pick tft-colour tft-fill  1+  300 ms key? until drop ;

\ Try it: tft-init tft-bars   or   tft-demo (Enter stops it)

\ tft2: colour display, ST7789 240 × 320, on SPI2: CS PB12, SCL PB13, SDA PB15, DC PC6, RES PC7
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB12 (pin 34) → display CS; VCC → 3V3, GND → GND, BLK → 3V3
$48000400 constant tft2-cs-port  \ GPIOB
12 constant tft2-cs-pin
\ PB13 (pin 35) → display SCL (the SPI clock, despite the name)
$48000400 constant tft2-scl-port  \ GPIOB
13 constant tft2-scl-pin
$40003800 constant tft2-spi  \ SPI2
5 constant tft2-scl-af
\ PB15 (pin 37) → display SDA (SPI data in, MOSI)
$48000400 constant tft2-sda-port  \ GPIOB
15 constant tft2-sda-pin
5 constant tft2-sda-af
\ PC6 (pin 38) → display DC (low: command, high: data)
$48000800 constant tft2-dc-port  \ GPIOC
6 constant tft2-dc-pin
\ PC7 (pin 39) → display RES (low: reset)
$48000800 constant tft2-res-port  \ GPIOC
7 constant tft2-res-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: tft2-sr ( -- u ) tft2-spi 8 + @ ;               \ SPI2 SR
: >tft2 ( b -- )                                  \ one byte out
  begin tft2-sr 2 and until  tft2-spi $C + c! ;   \ wait for TXE; c! writes one frame
: tft2-idle ( -- ) begin tft2-sr $80 and 0= until ; \ until BSY clears: the last byte is out
: tft2-dc ( f -- ) tft2-idle  1 tft2-dc-pin lshift \ DC changes only after the byte before it is out
  swap 0= if 16 lshift then tft2-dc-port $18 + ! ; \ BSRR: high for data, low for a command
: tft2-cmd ( c -- )                               \ CS low, the command with DC low; DC high for what follows
  1 tft2-cs-pin lshift 16 lshift tft2-cs-port $18 + !  0 tft2-dc >tft2  1 tft2-dc ;
: tft2-end ( -- ) tft2-idle 1 tft2-cs-pin lshift tft2-cs-port $18 + ! ; \ CS high
: >tft216 ( u -- ) dup 8 rshift >tft2 >tft2 ;     \ 16 bits, high byte first
: tft2-window ( x0 y0 x1 y1 -- )                  \ the pixels that follow fill this box, row by row
  >r >r  $2A tft2-cmd swap >tft216 r> >tft216     \ CASET: columns
  $2B tft2-cmd >tft216 r> >tft216  $2C tft2-cmd ; \ RASET: rows; RAMWR
: tft2-fill ( x0 y0 x1 y1 colour -- )             \ a box in one RGB565 colour
  >r 2over 2over tft2-window  rot - 1+ -rot swap - 1+ *
  r> swap 0 ?do dup >tft216 loop drop tft2-end ;
: tft2-pixel ( x y colour -- ) >r 2dup tft2-window r> >tft216 tft2-end ;
: tft2-reset ( -- )                               \ a pulse on RES, then the 120 ms it needs
  1 tft2-res-pin lshift 16 lshift tft2-res-port $18 + !  1 ms
  1 tft2-res-pin lshift tft2-res-port $18 + !  120 ms ;

: tft2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  1 14 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for SPI2
  1 tft2-cs-pin lshift tft2-cs-port $18 + !       \ BSRR: high before it becomes an output
  %11 tft2-cs-pin 2* lshift tft2-cs-port bic!     \ MODER: clear the two bits
  %01 tft2-cs-pin 2* lshift tft2-cs-port bis!     \ MODER: 01 = output
  1 tft2-cs-pin lshift tft2-cs-port 4 + bic!      \ OTYPER: push-pull
  %11 tft2-scl-pin 2* lshift tft2-scl-port bic!   \ MODER: clear the two bits
  %10 tft2-scl-pin 2* lshift tft2-scl-port bis!   \ MODER: 10 = alternate function
  1 tft2-scl-pin lshift tft2-scl-port 4 + bic!    \ OTYPER: push-pull
  $F tft2-scl-pin 8 - 4 * lshift tft2-scl-port $24 + bic! \ AFRH: clear the four bits
  tft2-scl-af tft2-scl-pin 8 - 4 * lshift tft2-scl-port $24 + bis! \ AFRH: AF5 = SPI2 SCK
  %11 tft2-sda-pin 2* lshift tft2-sda-port bic!   \ MODER: clear the two bits
  %10 tft2-sda-pin 2* lshift tft2-sda-port bis!   \ MODER: 10 = alternate function
  1 tft2-sda-pin lshift tft2-sda-port 4 + bic!    \ OTYPER: push-pull
  $F tft2-sda-pin 8 - 4 * lshift tft2-sda-port $24 + bic! \ AFRH: clear the four bits
  tft2-sda-af tft2-sda-pin 8 - 4 * lshift tft2-sda-port $24 + bis! \ AFRH: AF5 = SPI2 MOSI
  1 tft2-dc-pin lshift tft2-dc-port $18 + !       \ BSRR: high before it becomes an output
  %11 tft2-dc-pin 2* lshift tft2-dc-port bic!     \ MODER: clear the two bits
  %01 tft2-dc-pin 2* lshift tft2-dc-port bis!     \ MODER: 01 = output
  1 tft2-dc-pin lshift tft2-dc-port 4 + bic!      \ OTYPER: push-pull
  1 tft2-res-pin lshift tft2-res-port $18 + !     \ BSRR: high before it becomes an output
  %11 tft2-res-pin 2* lshift tft2-res-port bic!   \ MODER: clear the two bits
  %01 tft2-res-pin 2* lshift tft2-res-port bis!   \ MODER: 01 = output
  1 tft2-res-pin lshift tft2-res-port 4 + bic!    \ OTYPER: push-pull
  $1700 tft2-spi 4 + !                            \ CR2: 8-bit frames
  $344 tft2-spi !                                 \ CR1: master, SCK = 16 MHz / 2, SSM and SSI, on
  tft2-reset
  $11 tft2-cmd tft2-end 5 ms                      \ Sleep Out, then 5 ms before the next command
  $3A tft2-cmd $55 >tft2 tft2-end                 \ COLMOD: RGB565, 2 bytes a pixel (18-bit after reset)
  $36 tft2-cmd 0 >tft2 tft2-end                   \ MADCTL: row 0 at the top, column 0 on the left
  $21 tft2-cmd tft2-end                           \ inversion on: this IPS panel shows true colours so
  $29 tft2-cmd tft2-end ;                         \ Display On

\ Colour bars, and a box that changes colour until a key is pressed
create tft2-colours  $FFFF h, $FFE0 h, $07FF h, $07E0 h, $F81F h, $F800 h, $001F h, 0 h,
: tft2-colour ( n -- c ) 7 and 2* tft2-colours + h@ ;
: tft2-bars ( -- )
  8 0 do  i 30 * 0  i 30 * 29 + 319  i tft2-colour tft2-fill  loop ;
: tft2-demo ( -- )
  tft2-init tft2-bars
  0 begin  70 110 169 209 4 pick tft2-colour tft2-fill  1+  300 ms key? until drop ;

\ Try it: tft2-init tft2-bars   or   tft2-demo (Enter stops it)

tft-init tft-bars  70 70 169 169 0 tft-fill
#!tft 1
tft2-init tft2-bars  10 10 $F800 tft2-pixel
#!tft 2
