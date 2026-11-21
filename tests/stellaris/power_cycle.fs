\ A power cycle: the parts lose what they hold in RAM; the flash keeps its contents,
\ a sensor what it senses.
#!attach 1 oled PB8 PB9
#!attach 2 bmp280 PB8 PB9
#!attach 3 flash PB12 PB13 PB15 PB14
#!set 2 pressure_pa 95000
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
hex i2c-init AF 0 3C reg! 27 F4 76 reg! F4 76 reg@ . decimal
: bb-pins ( -- )
  %10 $4002104C bis!
  %1011 12 lshift GPIOB $18 + !  1 13 lshift 16 lshift GPIOB $18 + !   \ CS and DI high, CLK low
  $FF 24 lshift GPIOB bic!  %01000101 24 lshift GPIOB bis! ;          \ PB12, 13, 15 outputs; PB14 input
: pin! ( f n -- ) 1 swap lshift swap 0= if 16 lshift then GPIOB $18 + ! ;
: do? ( -- f ) GPIOB $10 + @ 1 14 lshift and 0<> ;
: bb ( b -- b ) 0 swap 8 0 do dup $80 and 15 pin!  1 13 pin!  swap 2* do? 1 and or swap  0 13 pin!  2* loop drop ;
: jedec ( -- ) 0 12 pin! $9F bb drop 0 bb hex. 0 bb hex. 0 bb hex. 1 12 pin! ;
bb-pins jedec
#!poke 3 100 48656C6C6F
#!oled 1
#!flash 3 100 5
#!powercycle
#!oled 1
#!flash 3 100 5
#!sensor 2
