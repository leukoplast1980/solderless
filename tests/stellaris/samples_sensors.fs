\ The sample code the Board tab gives for the sensors, as generated
\ (SamplesTest checks that it still matches): a BMP280 and an SCD41 sharing
\ I2C1, another BMP280 at 0x77 on I2C3.
#!attach 1 bmp280 PB8 PB9
#!attach 2 scd41 PB8 PB9
#!attach 3 bmp280 PC8 PC9 addr=0x77

\ baro: pressure sensor, BMP280, on I2C1: SCL PB8, SDA PB9, address $76
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB8 (pin 61) → BMP280 SCL; the module pulls it up (10 kΩ)
$48000400 constant baro-scl-port  \ GPIOB
8 constant baro-scl-pin
$40005400 constant baro-i2c  \ I2C1
$76 constant baro-addr
4 constant baro-scl-af
\ PB9 (pin 62) → BMP280 SDA; VCC → 3V3, GND → GND, CSB → 3V3 (I2C), SDO → GND (address 0x76)
$48000400 constant baro-sda-port  \ GPIOB
9 constant baro-sda-pin
4 constant baro-sda-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: baro-isr ( -- u ) baro-i2c $18 + @ ;            \ I2C ISR
: baro-go ( n flags -- )                          \ START a transfer of n bytes
  swap 16 lshift or  baro-addr 1 lshift or        \ CR2: NBYTES, the address, the flags
  1 13 lshift or  baro-i2c 4 + ! ;                \ START
1 25 lshift constant baro-auto                    \ AUTOEND: STOP after the last byte
1 10 lshift 1 25 lshift or constant baro-rd       \ RD_WRN: a read, and AUTOEND
: >baro ( b -- )                                  \ one byte out
  begin baro-isr 2 and until  baro-i2c $28 + ! ;  \ wait for TXIS, then TXDR
: baro> ( -- b )                                  \ one byte in
  begin baro-isr 4 and until  baro-i2c $24 + @ ;  \ wait for RXNE, then RXDR
: baro-sent ( -- ) begin baro-isr $40 and until ; \ TC: all bytes out, no STOP yet
: baro-end ( -- )                                 \ the transfer's STOP
  begin baro-isr $20 and until  $30 baro-i2c $1C + ! ; \ wait for STOPF; clear it and NACKF
: baro! ( v reg -- ) 2 baro-auto baro-go  >baro >baro  baro-end ; \ write a register
: baro-from ( reg n -- )                          \ point at reg, then read n bytes with baro>
  1 0 baro-go  swap >baro  baro-sent  baro-rd baro-go ; \ a repeated START turns it round
: baro@ ( reg -- b ) 1 baro-from baro> baro-end ; \ read a register

24 buffer: baro-cal                               \ this chip's calibration: dig_T1 .. dig_P9
: baro-u ( k -- u ) 2* baro-cal + dup c@ swap 1+ c@ 8 lshift or ; \ word k, little-endian, unsigned
: baro-s ( k -- n ) baro-u dup $8000 and if $10000 - then ; \ word k, signed
: baro-raw ( reg -- adc ) 3 baro-from             \ 20 bits: msb, lsb, xlsb
  baro> 12 lshift baro> 4 lshift or baro> 4 rshift or baro-end ;
0 variable baro-fine  0 variable baro-v1  0 variable baro-v2 \ t_fine, and two in-between values
: baro-t ( -- n )                                 \ °C in hundredths: the datasheet's 32-bit formula
  $FA baro-raw
  dup 3 rshift 0 baro-u 2* - 1 baro-s * 11 arshift
  swap 4 rshift 0 baro-u - dup * 12 arshift 2 baro-s * 14 arshift +
  dup baro-fine !  5 * 128 + 8 arshift ;
: baro-p ( -- pa )                                \ pascals; after baro-t, which sets t_fine
  baro-fine @ 1 arshift 64000 - baro-v1 !
  baro-v1 @ 2 arshift dup * 11 arshift 8 baro-s * baro-v2 !
  baro-v1 @ 7 baro-s * 2* baro-v2 +!
  baro-v2 @ 2 arshift 6 baro-s 16 lshift + baro-v2 !
  baro-v1 @ 2 arshift dup * 13 arshift 5 baro-s * 3 arshift
  baro-v1 @ 4 baro-s * 1 arshift + 18 arshift baro-v1 !
  32768 baro-v1 @ + 3 baro-u * 15 arshift baro-v1 !
  baro-v1 @ 0= if 0 exit then
  1048576 $F7 baro-raw - baro-v2 @ 12 arshift - 3125 *
  dup $80000000 u< if 2* baro-v1 @ u/mod nip else baro-v1 @ u/mod nip 2* then
  dup 3 rshift dup * 13 rshift 11 baro-s * 12 arshift baro-v1 !
  dup 2 rshift 10 baro-s * 13 arshift baro-v2 !
  baro-v1 @ baro-v2 @ + 9 baro-s + 4 arshift + ;
: baro.2 ( n -- ) dup >r abs s>d <# # # [char] . hold #S r> sign #> type space ; \ 2150 prints 21.50
: baro. ( -- ) baro-t baro.2 ." °C  " baro-p baro.2 ." hPa" ; \ a reading: 21.50 °C  1013.20 hPa

: baro-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 21 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for I2C1
  %11 baro-scl-pin 2* lshift baro-scl-port bic!   \ MODER: clear the two bits
  %10 baro-scl-pin 2* lshift baro-scl-port bis!   \ MODER: 10 = alternate function
  1 baro-scl-pin lshift baro-scl-port 4 + bis!    \ OTYPER: open-drain, as I2C needs
  $F baro-scl-pin 8 - 4 * lshift baro-scl-port $24 + bic! \ AFRH: clear the four bits
  baro-scl-af baro-scl-pin 8 - 4 * lshift baro-scl-port $24 + bis! \ AFRH: AF4 = I2C1 SCL
  %11 baro-sda-pin 2* lshift baro-sda-port bic!   \ MODER: clear the two bits
  %10 baro-sda-pin 2* lshift baro-sda-port bis!   \ MODER: 10 = alternate function
  1 baro-sda-pin lshift baro-sda-port 4 + bis!    \ OTYPER: open-drain, as I2C needs
  $F baro-sda-pin 8 - 4 * lshift baro-sda-port $24 + bic! \ AFRH: clear the four bits
  baro-sda-af baro-sda-pin 8 - 4 * lshift baro-sda-port $24 + bis! \ AFRH: AF4 = I2C1 SDA
  0 baro-i2c !                                    \ CR1: PE off while setting the timing
  $00310F13 baro-i2c $10 + !                      \ TIMINGR: SCL about 380 kHz from 16 MHz
  1 baro-i2c !                                    \ CR1: PE, on
  $D0 baro@ $58 <> if ." No BMP280 at " baro-addr hex. cr exit then \ its id: 58
  $88 24 baro-from  24 0 do baro> baro-cal i + c! loop  baro-end \ the calibration, 88 to 9F
  $10 $F5 baro!                                   \ config: standby 0.5 ms, IIR filter ×16
  $57 $F4 baro!                                   \ ctrl_meas: temperature ×2, pressure ×16, normal mode
  50 ms ;                                         \ the first measurement takes 37.5 ms

\ A reading every half second until a key is pressed
: baro-demo ( -- ) baro-init  begin baro. cr 500 ms key? until ;

\ Try it: baro-init baro.   or   baro-demo (Enter stops it)

\ co2: CO₂ sensor, SCD41, on I2C1: SCL PB8, SDA PB9, address $62
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB8 (pin 61) → SCD41 SCL; the module pulls it up (10 kΩ)
$48000400 constant co2-scl-port  \ GPIOB
8 constant co2-scl-pin
$40005400 constant co2-i2c  \ I2C1
$62 constant co2-addr
4 constant co2-scl-af
\ PB9 (pin 62) → SCD41 SDA; VDD → 3V3, GND → GND, address 0x62
$48000400 constant co2-sda-port  \ GPIOB
9 constant co2-sda-pin
4 constant co2-sda-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: co2-isr ( -- u ) co2-i2c $18 + @ ;              \ I2C ISR
: co2-go ( n flags -- )                           \ START a transfer of n bytes
  swap 16 lshift or  co2-addr 1 lshift or         \ CR2: NBYTES, the address, the flags
  1 13 lshift or  co2-i2c 4 + ! ;                 \ START
1 25 lshift constant co2-auto                     \ AUTOEND: STOP after the last byte
1 10 lshift 1 25 lshift or constant co2-rd        \ RD_WRN: a read, and AUTOEND
: >co2 ( b -- )                                   \ one byte out
  begin co2-isr 2 and until  co2-i2c $28 + ! ;    \ wait for TXIS, then TXDR
: co2> ( -- b )                                   \ one byte in
  begin co2-isr 4 and until  co2-i2c $24 + @ ;    \ wait for RXNE, then RXDR
: co2-sent ( -- ) begin co2-isr $40 and until ;   \ TC: all bytes out, no STOP yet
: co2-end ( -- )                                  \ the transfer's STOP
  begin co2-isr $20 and until  $30 co2-i2c $1C + ! ; \ wait for STOPF; clear it and NACKF
: co2-crc8 ( c b -- c )                           \ CRC-8, polynomial 31, one byte in
  xor 8 0 do dup $80 and if 2* $31 xor else 2* then $FF and loop ;
: co2-crc ( u -- c ) $FF over 8 rshift co2-crc8 swap $FF and co2-crc8 ; \ of a 16-bit word, from FF
: co2-cmd ( c -- ) 2 co2-auto co2-go  dup 8 rshift >co2 >co2  co2-end ; \ a 16-bit command
: co2-cmd+ ( u c -- )                             \ a command with one word, its CRC after it
  5 co2-auto co2-go  dup 8 rshift >co2 >co2  dup 8 rshift >co2 dup >co2 co2-crc >co2  co2-end ;
: co2-answer ( n -- flag )                        \ read n words; false if it does not answer
  3 * co2-rd co2-go  begin co2-isr %10100 and until \ RXNE, or NACKF
  co2-isr $10 and if co2-end false else true then ;
: co2-word ( -- u ) co2> 8 lshift co2> or  co2> drop ; \ a word; its CRC not checked here

0 variable co2-ppm  0 variable co2-t  0 variable co2-rh
: co2-ready? ( -- flag )                          \ get_data_ready_status
  $E4B8 co2-cmd 1 ms  1 co2-answer if co2-word co2-end $7FF and 0<> else false then ;
: co2-read ( -- flag )                            \ read_measurement: CO₂, temperature, humidity
  $EC05 co2-cmd 1 ms  3 co2-answer 0= if false exit then \ no new data: it does not answer
  co2-word co2-ppm !  co2-word co2-t !  co2-word co2-rh !  co2-end true ;
: co2.2 ( n -- ) dup >r abs s>d <# # # [char] . hold #S r> sign #> type space ; \ 2150 prints 21.50
: co2. ( -- ) co2-ppm @ . ." ppm  "               \ a reading: 820 ppm  22.00 °C  45.00 %
  co2-t @ 17500 * 65535 / 4500 - co2.2 ." °C  "  co2-rh @ 10000 * 65535 / co2.2 ." %" ;

: co2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 21 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for I2C1
  %11 co2-scl-pin 2* lshift co2-scl-port bic!     \ MODER: clear the two bits
  %10 co2-scl-pin 2* lshift co2-scl-port bis!     \ MODER: 10 = alternate function
  1 co2-scl-pin lshift co2-scl-port 4 + bis!      \ OTYPER: open-drain, as I2C needs
  $F co2-scl-pin 8 - 4 * lshift co2-scl-port $24 + bic! \ AFRH: clear the four bits
  co2-scl-af co2-scl-pin 8 - 4 * lshift co2-scl-port $24 + bis! \ AFRH: AF4 = I2C1 SCL
  %11 co2-sda-pin 2* lshift co2-sda-port bic!     \ MODER: clear the two bits
  %10 co2-sda-pin 2* lshift co2-sda-port bis!     \ MODER: 10 = alternate function
  1 co2-sda-pin lshift co2-sda-port 4 + bis!      \ OTYPER: open-drain, as I2C needs
  $F co2-sda-pin 8 - 4 * lshift co2-sda-port $24 + bic! \ AFRH: clear the four bits
  co2-sda-af co2-sda-pin 8 - 4 * lshift co2-sda-port $24 + bis! \ AFRH: AF4 = I2C1 SDA
  0 co2-i2c !                                     \ CR1: PE off while setting the timing
  $00310F13 co2-i2c $10 + !                       \ TIMINGR: SCL about 380 kHz from 16 MHz
  1 co2-i2c !                                     \ CR1: PE, on
  $3F86 co2-cmd 500 ms                            \ stop_periodic_measurement: it may still run from before
  $21B1 co2-cmd ;                                 \ start_periodic_measurement: a reading every 5 s

\ The readings as they come, until a key is pressed
: co2-demo ( -- ) co2-init  begin co2-ready? if co2-read drop co2. cr then 200 ms key? until ;

\ Try it: co2-init 5100 ms co2-read . co2.   or   co2-demo (Enter stops it)

\ baro2: pressure sensor, BMP280, on I2C3: SCL PC8, SDA PC9, address $77
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PC8 (pin 40) → BMP280 SCL; the module pulls it up (10 kΩ)
$48000800 constant baro2-scl-port  \ GPIOC
8 constant baro2-scl-pin
$40007800 constant baro2-i2c  \ I2C3
$77 constant baro2-addr
8 constant baro2-scl-af
\ PC9 (pin 41) → BMP280 SDA; VCC → 3V3, GND → GND, CSB → 3V3 (I2C), SDO → 3V3 (address 0x77)
$48000800 constant baro2-sda-port  \ GPIOC
9 constant baro2-sda-pin
8 constant baro2-sda-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: baro2-isr ( -- u ) baro2-i2c $18 + @ ;          \ I2C ISR
: baro2-go ( n flags -- )                         \ START a transfer of n bytes
  swap 16 lshift or  baro2-addr 1 lshift or       \ CR2: NBYTES, the address, the flags
  1 13 lshift or  baro2-i2c 4 + ! ;               \ START
1 25 lshift constant baro2-auto                   \ AUTOEND: STOP after the last byte
1 10 lshift 1 25 lshift or constant baro2-rd      \ RD_WRN: a read, and AUTOEND
: >baro2 ( b -- )                                 \ one byte out
  begin baro2-isr 2 and until  baro2-i2c $28 + ! ; \ wait for TXIS, then TXDR
: baro2> ( -- b )                                 \ one byte in
  begin baro2-isr 4 and until  baro2-i2c $24 + @ ; \ wait for RXNE, then RXDR
: baro2-sent ( -- ) begin baro2-isr $40 and until ; \ TC: all bytes out, no STOP yet
: baro2-end ( -- )                                \ the transfer's STOP
  begin baro2-isr $20 and until  $30 baro2-i2c $1C + ! ; \ wait for STOPF; clear it and NACKF
: baro2! ( v reg -- ) 2 baro2-auto baro2-go  >baro2 >baro2  baro2-end ; \ write a register
: baro2-from ( reg n -- )                         \ point at reg, then read n bytes with baro2>
  1 0 baro2-go  swap >baro2  baro2-sent  baro2-rd baro2-go ; \ a repeated START turns it round
: baro2@ ( reg -- b ) 1 baro2-from baro2> baro2-end ; \ read a register

24 buffer: baro2-cal                              \ this chip's calibration: dig_T1 .. dig_P9
: baro2-u ( k -- u ) 2* baro2-cal + dup c@ swap 1+ c@ 8 lshift or ; \ word k, little-endian, unsigned
: baro2-s ( k -- n ) baro2-u dup $8000 and if $10000 - then ; \ word k, signed
: baro2-raw ( reg -- adc ) 3 baro2-from           \ 20 bits: msb, lsb, xlsb
  baro2> 12 lshift baro2> 4 lshift or baro2> 4 rshift or baro2-end ;
0 variable baro2-fine  0 variable baro2-v1  0 variable baro2-v2 \ t_fine, and two in-between values
: baro2-t ( -- n )                                \ °C in hundredths: the datasheet's 32-bit formula
  $FA baro2-raw
  dup 3 rshift 0 baro2-u 2* - 1 baro2-s * 11 arshift
  swap 4 rshift 0 baro2-u - dup * 12 arshift 2 baro2-s * 14 arshift +
  dup baro2-fine !  5 * 128 + 8 arshift ;
: baro2-p ( -- pa )                               \ pascals; after baro2-t, which sets t_fine
  baro2-fine @ 1 arshift 64000 - baro2-v1 !
  baro2-v1 @ 2 arshift dup * 11 arshift 8 baro2-s * baro2-v2 !
  baro2-v1 @ 7 baro2-s * 2* baro2-v2 +!
  baro2-v2 @ 2 arshift 6 baro2-s 16 lshift + baro2-v2 !
  baro2-v1 @ 2 arshift dup * 13 arshift 5 baro2-s * 3 arshift
  baro2-v1 @ 4 baro2-s * 1 arshift + 18 arshift baro2-v1 !
  32768 baro2-v1 @ + 3 baro2-u * 15 arshift baro2-v1 !
  baro2-v1 @ 0= if 0 exit then
  1048576 $F7 baro2-raw - baro2-v2 @ 12 arshift - 3125 *
  dup $80000000 u< if 2* baro2-v1 @ u/mod nip else baro2-v1 @ u/mod nip 2* then
  dup 3 rshift dup * 13 rshift 11 baro2-s * 12 arshift baro2-v1 !
  dup 2 rshift 10 baro2-s * 13 arshift baro2-v2 !
  baro2-v1 @ baro2-v2 @ + 9 baro2-s + 4 arshift + ;
: baro2.2 ( n -- ) dup >r abs s>d <# # # [char] . hold #S r> sign #> type space ; \ 2150 prints 21.50
: baro2. ( -- ) baro2-t baro2.2 ." °C  " baro2-p baro2.2 ." hPa" ; \ a reading: 21.50 °C  1013.20 hPa

: baro2-init ( -- )
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  1 30 lshift $40021058 bis!                      \ RCC APB1ENR1: clock for I2C3
  %11 baro2-scl-pin 2* lshift baro2-scl-port bic! \ MODER: clear the two bits
  %10 baro2-scl-pin 2* lshift baro2-scl-port bis! \ MODER: 10 = alternate function
  1 baro2-scl-pin lshift baro2-scl-port 4 + bis!  \ OTYPER: open-drain, as I2C needs
  $F baro2-scl-pin 8 - 4 * lshift baro2-scl-port $24 + bic! \ AFRH: clear the four bits
  baro2-scl-af baro2-scl-pin 8 - 4 * lshift baro2-scl-port $24 + bis! \ AFRH: AF8 = I2C3 SCL
  %11 baro2-sda-pin 2* lshift baro2-sda-port bic! \ MODER: clear the two bits
  %10 baro2-sda-pin 2* lshift baro2-sda-port bis! \ MODER: 10 = alternate function
  1 baro2-sda-pin lshift baro2-sda-port 4 + bis!  \ OTYPER: open-drain, as I2C needs
  $F baro2-sda-pin 8 - 4 * lshift baro2-sda-port $24 + bic! \ AFRH: clear the four bits
  baro2-sda-af baro2-sda-pin 8 - 4 * lshift baro2-sda-port $24 + bis! \ AFRH: AF8 = I2C3 SDA
  0 baro2-i2c !                                   \ CR1: PE off while setting the timing
  $00310F13 baro2-i2c $10 + !                     \ TIMINGR: SCL about 380 kHz from 16 MHz
  1 baro2-i2c !                                   \ CR1: PE, on
  $D0 baro2@ $58 <> if ." No BMP280 at " baro2-addr hex. cr exit then \ its id: 58
  $88 24 baro2-from  24 0 do baro2> baro2-cal i + c! loop  baro2-end \ the calibration, 88 to 9F
  $10 $F5 baro2!                                  \ config: standby 0.5 ms, IIR filter ×16
  $57 $F4 baro2!                                  \ ctrl_meas: temperature ×2, pressure ×16, normal mode
  50 ms ;                                         \ the first measurement takes 37.5 ms

\ A reading every half second until a key is pressed
: baro2-demo ( -- ) baro2-init  begin baro2. cr 500 ms key? until ;

\ Try it: baro2-init baro2.   or   baro2-demo (Enter stops it)

baro-init baro. cr
#!set 1 pressure_pa 95000
#!set 1 temp_c -5.5
2000 ms baro. cr
baro2-init baro2. cr
co2-init 5100 ms co2-ready? . co2-read . co2. cr
co2-read .
#!set 2 breath 1
5000 ms co2-read . co2. cr
#!sensor 1
#!sensor 2
