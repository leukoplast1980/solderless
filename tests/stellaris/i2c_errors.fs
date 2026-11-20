\ I2C mistakes the emulator points out: no pull-ups (no START goes out),
\ SCL faster than the part allows (it does not answer), push-pull pins.
#!attach 1 oled PB8 PB9 pull=0
$48000400 constant GPIOB
$40005400 constant I2C1
: i2c-pins ( -- )
  %10 $4002104C bis!  1 21 lshift $40021058 bis!
  %1111 16 lshift GPIOB bic!  %1010 16 lshift GPIOB bis!
  %11 8 lshift GPIOB 4 + bis!  $FF GPIOB $24 + bic!  $44 GPIOB $24 + bis! ;
: isr@ ( -- u ) I2C1 $18 + @ ;
: probe ( addr -- isr )  \ address only, then whatever ISR says after a while
  1 lshift 1 25 lshift or 1 13 lshift or I2C1 4 + !  2000 0 do loop  isr@  $30 I2C1 $1C + ! ;
i2c-pins  $00310F13 I2C1 $10 + !  1 I2C1 !
$3C probe hex.
#!clear
#!attach 1 oled PB8 PB9
0 I2C1 !  1 I2C1 !
$3C probe hex.
0 I2C1 !  0 I2C1 $10 + !  1 I2C1 !
$3C probe hex.
0 I2C1 !  $00310F13 I2C1 $10 + !  1 I2C1 !
%11 8 lshift GPIOB 4 + bic!
$3C probe hex.
