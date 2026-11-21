\ I2C1 as a master, writing to an SSD1306 OLED on PB8 (SCL) and PB9 (SDA):
\ address probes with ACK and NACK, the init sequence, a picture, inverse.
#!attach 1 oled PB8 PB9
$48000400 constant GPIOB
$40005400 constant I2C1
: i2c-pins ( -- )
  %10 $4002104C bis!                         \ GPIOB clock
  1 21 lshift $40021058 bis!                 \ I2C1 clock
  %1111 16 lshift GPIOB bic!  %1010 16 lshift GPIOB bis!   \ PB8 PB9 alternate function
  %11 8 lshift GPIOB 4 + bis!                \ open-drain
  $FF GPIOB $24 + bic!  $44 GPIOB $24 + bis! ;  \ AF4
: i2c-init ( -- ) i2c-pins  0 I2C1 !  $00310F13 I2C1 $10 + !  1 I2C1 ! ;
: isr@ ( -- u ) I2C1 $18 + @ ;
: i2c-go ( n addr -- )  \ write n bytes, STOP after them
  1 lshift swap 16 lshift or  1 25 lshift or  1 13 lshift or  I2C1 4 + ! ;
: >i2c ( b -- ) begin isr@ 2 and until  I2C1 $28 + ! ;
: i2c-end ( -- ) begin isr@ $20 and until  $20 I2C1 $1C + ! ;
: probe ( addr -- f )  \ address only: does it answer?
  0 swap i2c-go  begin isr@ $20 and until  isr@ $10 and 0=  $30 I2C1 $1C + ! ;
: cmd ( c -- ) 2 $3C i2c-go  0 >i2c  >i2c  i2c-end ;
: cmd2 ( c c -- ) swap cmd cmd ;
: oled-init ( -- )
  $AE cmd  $D5 $80 cmd2  $A8 $3F cmd2  $D3 0 cmd2  $40 cmd  $8D $14 cmd2
  $20 0 cmd2  $A1 cmd  $C8 cmd  $DA $12 cmd2  $81 $CF cmd2  $D9 $F1 cmd2
  $DB $40 cmd2  $A4 cmd  $A6 cmd  $AF cmd ;
: home ( -- ) $21 cmd 0 cmd 127 cmd  $22 cmd 0 cmd 7 cmd ;
: page! ( b -- )  \ one page of 128 bytes, all b
  129 $3C i2c-go  $40 >i2c  128 0 do dup >i2c loop drop  i2c-end ;
: frame ( -- )  \ a border and a checker in the middle
  home
  8 0 do
    129 $3C i2c-go  $40 >i2c
    128 0 do
      i 0= i 127 = or if $FF else
      j 0= if $01 else j 7 = if $80 else
      i 48 > i 80 < and j 2 > and j 5 < and if i 1 and if $AA else $55 then else 0 then then then then
      >i2c
    loop i2c-end
  loop ;
i2c-init
$3C probe .  $3D probe .
#!oled 1
oled-init
#!oled 1
frame
#!oled 1
$A7 cmd
#!oled 1
