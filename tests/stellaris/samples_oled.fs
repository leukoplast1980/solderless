\ The sample code the Board tab gives for OLED displays, as generated
\ (SamplesTest checks that it still matches): I2C1 at 0x3C, I2C3 at 0x3D.
#!attach 1 oled PB8 PB9
#!attach 2 oled PC8 PC9 addr=0x3D

\ oled: OLED display, SSD1306 128 × 64, on I2C1: SCL PB8, SDA PB9, address $3C
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB8 (pin 61) → OLED SCL; the module pulls it up (4.7 kΩ)
$48000400 constant oled-scl-port  \ GPIOB
8 constant oled-scl-pin
$40005400 constant oled-i2c  \ I2C1
$3C constant oled-addr
4 constant oled-scl-af
\ PB9 (pin 62) → OLED SDA, pulled up on the module; VCC → 3V3, GND → GND, address 0x3C (SA0 to GND)
$48000400 constant oled-sda-port  \ GPIOB
9 constant oled-sda-pin
4 constant oled-sda-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: oled-isr ( -- u ) oled-i2c $18 + @ ;            \ I2C ISR
: oled-go ( n -- )                                \ write n bytes to the OLED, STOP after them
  16 lshift  oled-addr 1 lshift or                \ CR2: NBYTES, the address
  1 25 lshift or  1 13 lshift or  oled-i2c 4 + ! ; \ AUTOEND, START
: >oled ( b -- )                                  \ one byte
  begin oled-isr 2 and until  oled-i2c $28 + ! ;  \ wait for TXIS, then TXDR
: oled-end ( -- )                                 \ the transfer's STOP
  begin oled-isr $20 and until  $20 oled-i2c $1C + ! ; \ wait for STOPF, clear it in ICR
: oled? ( -- flag )                               \ does the OLED answer at its address?
  0 oled-go  begin oled-isr $20 and until         \ the address alone
  oled-isr $10 and 0=  $30 oled-i2c $1C + ! ;     \ no NACKF: it answered
: oled-cmd ( c -- ) 2 oled-go  0 >oled  >oled  oled-end ; \ control byte 00: a command follows

1024 buffer: oled-buf                     \ the picture: 8 pages of 128 columns
: oled-clear ( -- ) oled-buf 1024 0 fill ;
: oled-pixel ( x y -- )                   \ x 0..127 from the left, y 0..63 from the top
  over 128 u< over 64 u< and 0= if 2drop exit then
  dup 7 and 1 swap lshift -rot            \ the bit in its byte
  3 rshift 7 lshift + oled-buf + cbis! ;  \ the byte: page y/8, column x
: oled-show ( -- )                        \ the picture to the display
  $21 oled-cmd 0 oled-cmd 127 oled-cmd    \ columns 0 to 127
  $22 oled-cmd 0 oled-cmd 7 oled-cmd      \ pages 0 to 7
  8 0 do
    129 oled-go  $40 >oled                \ control byte 40: data follows
    128 0 do  j 7 lshift i + oled-buf + c@ >oled  loop
    oled-end
  loop ;

: oled-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 21 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for I2C1
  %11 oled-scl-pin 2* lshift oled-scl-port bic!   \ MODER: clear the two bits
  %10 oled-scl-pin 2* lshift oled-scl-port bis!   \ MODER: 10 = alternate function
  1 oled-scl-pin lshift oled-scl-port 4 + bis!    \ OTYPER: open-drain, as I2C needs
  $F oled-scl-pin 8 - 4 * lshift oled-scl-port $24 + bic! \ AFRH: clear the four bits
  oled-scl-af oled-scl-pin 8 - 4 * lshift oled-scl-port $24 + bis! \ AFRH: AF4 = I2C1 SCL
  %11 oled-sda-pin 2* lshift oled-sda-port bic!   \ MODER: clear the two bits
  %10 oled-sda-pin 2* lshift oled-sda-port bis!   \ MODER: 10 = alternate function
  1 oled-sda-pin lshift oled-sda-port 4 + bis!    \ OTYPER: open-drain, as I2C needs
  $F oled-sda-pin 8 - 4 * lshift oled-sda-port $24 + bic! \ AFRH: clear the four bits
  oled-sda-af oled-sda-pin 8 - 4 * lshift oled-sda-port $24 + bis! \ AFRH: AF4 = I2C1 SDA
  0 oled-i2c !                                    \ CR1: PE off while setting the timing
  $00310F13 oled-i2c $10 + !                      \ TIMINGR: SCL about 380 kHz from 16 MHz
  1 oled-i2c !                                    \ CR1: PE, on
  oled? 0= if ." The OLED does not answer at " oled-addr hex. cr exit then
  $AE oled-cmd                                    \ display off while setting it up
  $D5 oled-cmd $80 oled-cmd                       \ clock: the default
  $A8 oled-cmd 63 oled-cmd                        \ multiplex: 64 lines
  $D3 oled-cmd 0 oled-cmd  $40 oled-cmd           \ no offset, start at line 0
  $8D oled-cmd $14 oled-cmd                       \ charge pump on: without it the panel stays dark
  $20 oled-cmd 0 oled-cmd                         \ horizontal addressing
  $A1 oled-cmd  $C8 oled-cmd                      \ column 0 on the left, row 0 at the top
  $DA oled-cmd $12 oled-cmd                       \ COM pins as 128 × 64 panels are wired
  $81 oled-cmd $CF oled-cmd                       \ contrast
  $A4 oled-cmd  $A6 oled-cmd                      \ show the RAM, not inverted
  oled-clear oled-show                            \ the RAM holds noise at power-on
  $AF oled-cmd ;                                  \ display on

\ A ball bouncing inside a frame until a key is pressed
10 variable oled-x  10 variable oled-y  2 variable oled-dx  1 variable oled-dy
: oled-frame ( -- )
  128 0 do i 0 oled-pixel  i 63 oled-pixel loop
  64 0 do 0 i oled-pixel  127 i oled-pixel loop ;
: oled-ball ( -- )
  4 0 do 4 0 do  oled-x @ i +  oled-y @ j +  oled-pixel  loop loop ;
: oled-move ( -- )
  oled-dx @ oled-x +!  oled-x @ 2 < oled-x @ 122 > or if oled-dx @ negate oled-dx ! then
  oled-dy @ oled-y +!  oled-y @ 2 < oled-y @ 58 > or if oled-dy @ negate oled-dy ! then ;
: oled-demo ( -- )
  oled-init  oled? 0= if exit then
  begin oled-clear oled-frame oled-ball oled-show oled-move key? until ;

\ Try it: oled-init 10 20 oled-pixel oled-show   or   oled-demo (Enter stops it)

\ oled2: OLED display, SSD1306 128 × 64, on I2C3: SCL PC8, SDA PC9, address $3D
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PC8 (pin 40) → OLED SCL; the module pulls it up (4.7 kΩ)
$48000800 constant oled2-scl-port  \ GPIOC
8 constant oled2-scl-pin
$40007800 constant oled2-i2c  \ I2C3
$3D constant oled2-addr
8 constant oled2-scl-af
\ PC9 (pin 41) → OLED SDA, pulled up on the module; VCC → 3V3, GND → GND, address 0x3D (SA0 to 3V3)
$48000800 constant oled2-sda-port  \ GPIOC
9 constant oled2-sda-pin
8 constant oled2-sda-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: oled2-isr ( -- u ) oled2-i2c $18 + @ ;          \ I2C ISR
: oled2-go ( n -- )                               \ write n bytes to the OLED, STOP after them
  16 lshift  oled2-addr 1 lshift or               \ CR2: NBYTES, the address
  1 25 lshift or  1 13 lshift or  oled2-i2c 4 + ! ; \ AUTOEND, START
: >oled2 ( b -- )                                 \ one byte
  begin oled2-isr 2 and until  oled2-i2c $28 + ! ; \ wait for TXIS, then TXDR
: oled2-end ( -- )                                \ the transfer's STOP
  begin oled2-isr $20 and until  $20 oled2-i2c $1C + ! ; \ wait for STOPF, clear it in ICR
: oled2? ( -- flag )                              \ does the OLED answer at its address?
  0 oled2-go  begin oled2-isr $20 and until       \ the address alone
  oled2-isr $10 and 0=  $30 oled2-i2c $1C + ! ;   \ no NACKF: it answered
: oled2-cmd ( c -- ) 2 oled2-go  0 >oled2  >oled2  oled2-end ; \ control byte 00: a command follows

1024 buffer: oled2-buf                     \ the picture: 8 pages of 128 columns
: oled2-clear ( -- ) oled2-buf 1024 0 fill ;
: oled2-pixel ( x y -- )                   \ x 0..127 from the left, y 0..63 from the top
  over 128 u< over 64 u< and 0= if 2drop exit then
  dup 7 and 1 swap lshift -rot             \ the bit in its byte
  3 rshift 7 lshift + oled2-buf + cbis! ;  \ the byte: page y/8, column x
: oled2-show ( -- )                        \ the picture to the display
  $21 oled2-cmd 0 oled2-cmd 127 oled2-cmd  \ columns 0 to 127
  $22 oled2-cmd 0 oled2-cmd 7 oled2-cmd    \ pages 0 to 7
  8 0 do
    129 oled2-go  $40 >oled2               \ control byte 40: data follows
    128 0 do  j 7 lshift i + oled2-buf + c@ >oled2  loop
    oled2-end
  loop ;

: oled2-init ( -- )
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  1 30 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for I2C3
  %11 oled2-scl-pin 2* lshift oled2-scl-port bic! \ MODER: clear the two bits
  %10 oled2-scl-pin 2* lshift oled2-scl-port bis! \ MODER: 10 = alternate function
  1 oled2-scl-pin lshift oled2-scl-port 4 + bis!  \ OTYPER: open-drain, as I2C needs
  $F oled2-scl-pin 8 - 4 * lshift oled2-scl-port $24 + bic! \ AFRH: clear the four bits
  oled2-scl-af oled2-scl-pin 8 - 4 * lshift oled2-scl-port $24 + bis! \ AFRH: AF8 = I2C3 SCL
  %11 oled2-sda-pin 2* lshift oled2-sda-port bic! \ MODER: clear the two bits
  %10 oled2-sda-pin 2* lshift oled2-sda-port bis! \ MODER: 10 = alternate function
  1 oled2-sda-pin lshift oled2-sda-port 4 + bis!  \ OTYPER: open-drain, as I2C needs
  $F oled2-sda-pin 8 - 4 * lshift oled2-sda-port $24 + bic! \ AFRH: clear the four bits
  oled2-sda-af oled2-sda-pin 8 - 4 * lshift oled2-sda-port $24 + bis! \ AFRH: AF8 = I2C3 SDA
  0 oled2-i2c !                                   \ CR1: PE off while setting the timing
  $00310F13 oled2-i2c $10 + !                     \ TIMINGR: SCL about 380 kHz from 16 MHz
  1 oled2-i2c !                                   \ CR1: PE, on
  oled2? 0= if ." The OLED does not answer at " oled2-addr hex. cr exit then
  $AE oled2-cmd                                   \ display off while setting it up
  $D5 oled2-cmd $80 oled2-cmd                     \ clock: the default
  $A8 oled2-cmd 63 oled2-cmd                      \ multiplex: 64 lines
  $D3 oled2-cmd 0 oled2-cmd  $40 oled2-cmd        \ no offset, start at line 0
  $8D oled2-cmd $14 oled2-cmd                     \ charge pump on: without it the panel stays dark
  $20 oled2-cmd 0 oled2-cmd                       \ horizontal addressing
  $A1 oled2-cmd  $C8 oled2-cmd                    \ column 0 on the left, row 0 at the top
  $DA oled2-cmd $12 oled2-cmd                     \ COM pins as 128 × 64 panels are wired
  $81 oled2-cmd $CF oled2-cmd                     \ contrast
  $A4 oled2-cmd  $A6 oled2-cmd                    \ show the RAM, not inverted
  oled2-clear oled2-show                          \ the RAM holds noise at power-on
  $AF oled2-cmd ;                                 \ display on

\ A ball bouncing inside a frame until a key is pressed
10 variable oled2-x  10 variable oled2-y  2 variable oled2-dx  1 variable oled2-dy
: oled2-frame ( -- )
  128 0 do i 0 oled2-pixel  i 63 oled2-pixel loop
  64 0 do 0 i oled2-pixel  127 i oled2-pixel loop ;
: oled2-ball ( -- )
  4 0 do 4 0 do  oled2-x @ i +  oled2-y @ j +  oled2-pixel  loop loop ;
: oled2-move ( -- )
  oled2-dx @ oled2-x +!  oled2-x @ 2 < oled2-x @ 122 > or if oled2-dx @ negate oled2-dx ! then
  oled2-dy @ oled2-y +!  oled2-y @ 2 < oled2-y @ 58 > or if oled2-dy @ negate oled2-dy ! then ;
: oled2-demo ( -- )
  oled2-init  oled2? 0= if exit then
  begin oled2-clear oled2-frame oled2-ball oled2-show oled2-move key? until ;

\ Try it: oled2-init 10 20 oled2-pixel oled2-show   or   oled2-demo (Enter stops it)

oled-init  oled? .  10 20 oled-pixel oled-frame oled-ball oled-show
: diagonal ( -- ) 20 0 do i 2* 10 + i oled2-pixel loop ;
oled2-init  oled2? .  oled2-frame diagonal oled2-show
oled-move oled-move oled-x @ . oled-y @ .
#!oled 1
#!oled 2
