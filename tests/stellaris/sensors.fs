\ A BMP280 (0x76) and an SCD41 (0x62) sharing I2C1 on PB8 (SCL) and PB9 (SDA).
\ BMP280: its id, data before any measurement, calibration, forced and normal
\ mode, a temperature turned into hundredths of a degree with the datasheet's
\ formula. SCD41: serial number, commands with CRCs, periodic measurement,
\ the rules on what it acknowledges.
#!attach 1 bmp280 PB8 PB9
#!attach 2 scd41 PB8 PB9
$48000400 constant GPIOB
$40005400 constant I2C1
: ms ( n -- ) 15999 $E000E014 !  0 $E000E018 !  5 $E000E010 !
  0 ?do begin $E000E010 @ $10000 and until loop  0 $E000E010 ! ;
: i2c-init ( -- )
  %10 $4002104C bis!  1 21 lshift $40021058 bis!
  %1111 16 lshift GPIOB bic!  %1010 16 lshift GPIOB bis!  %11 8 lshift GPIOB 4 + bis!
  $FF GPIOB $24 + bic!  $44 GPIOB $24 + bis!
  0 I2C1 !  $00310F13 I2C1 $10 + !  1 I2C1 ! ;
: isr@ ( -- u ) I2C1 $18 + @ ;
: go ( n addr flags -- ) >r 1 lshift swap 16 lshift or r> or 1 13 lshift or I2C1 4 + ! ;
: >i2c ( b -- ) begin isr@ %10010 and until  isr@ $10 and 0= if I2C1 $28 + ! else drop then ;
: i2c> ( -- b ) begin isr@ %10100 and until  I2C1 $24 + @ ;
: end ( -- f ) begin isr@ $20 and until  isr@ $10 and 0=  $30 I2C1 $1C + ! ;  \ STOP; acknowledged?
: tc ( -- ) begin isr@ %1010000 and until ;
: reg! ( v reg addr -- ) 2 swap 1 25 lshift go >i2c >i2c end drop ;
: wr1 ( reg addr -- ) 1 swap 0 go >i2c tc ;                     \ the register pointer; no STOP
: rdn ( n addr -- ) 1 10 lshift 1 25 lshift or go ;            \ repeated START, read n, STOP
: regs ( reg addr n -- )  \ the registers from reg on, in hex
  >r swap over wr1  r@ swap rdn  r> 0 ?do i2c> . loop  end drop ;
: reg@ ( reg addr -- b ) tuck wr1 1 swap rdn i2c> end drop ;
hex
i2c-init
$D0 $76 reg@ .
$F7 $76 6 regs
$88 $76 6 regs
\ T: the datasheet's 32-bit formula, in hundredths of a degree
decimal
0 variable adc
: t100 ( -- n )
  adc @ 3 rshift 27504 2* - 26435 * 11 arshift
  adc @ 4 rshift 27504 - dup * 12 arshift -1000 * 14 arshift +
  5 * 128 + 8 arshift ;
: raw ( -- ) $FA $76 wr1  3 $76 rdn
  i2c> 12 lshift i2c> 4 lshift or i2c> 4 rshift or adc ! end drop ;
$25 $F4 $76 reg!  $F3 $76 reg@ .
10 ms  $F3 $76 reg@ .  raw t100 .
#!set 1 temp_c 30.25
$B7 $F4 $76 reg!  $10 $F5 $76 reg!  100 ms raw t100 .
#!sensor 1
\ The SCD41: 16-bit commands;  got with a CRC-8 after each
: cmd ( c -- ) 2 $62 1 25 lshift go  dup 8 rshift >i2c >i2c end . ;
: got ( n -- )  \ n  got with their CRCs, or "nack" when it does not answer
  3 * dup $62 rdn  begin isr@ %10100 and until
  isr@ $10 and if drop end drop ." nack " exit then
  0 ?do i2c> . loop end drop ;
hex
$3682 cmd 1 ms 3 got
$EC05 cmd 1 ms 3 got
$21B1 cmd
$241D cmd
$E4B8 cmd 1 ms 1 got
decimal 5100 ms hex
$E4B8 cmd 1 ms 1 got
$EC05 cmd 3 got
$EC05 cmd 1 ms 3 got
$EC05 cmd 1 ms 3 got
$3F86 cmd $2318 cmd
decimal
#!set 2 co2_ppm 1200
#!sensor 2
