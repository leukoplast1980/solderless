\ The same OLED on bit-banged I2C: the decoder on the pins acknowledges,
\ and the status byte (D6: display off) reads back through IDR.
#!attach 1 oled PB8 PB9
$48000400 constant GPIOB
: bb-pins ( -- )
  %10 $4002104C bis!
  %11 8 lshift GPIOB 4 + bis!                      \ open-drain
  %1111 16 lshift GPIOB $18 + !                     \ released (high) before they become outputs
  %1111 16 lshift GPIOB bic!  %0101 16 lshift GPIOB bis! ;   \ outputs
: scl-hi 1 8 lshift GPIOB $18 + ! ;
: scl-lo 1 8 lshift 16 lshift GPIOB $18 + ! ;
: sda-hi 1 9 lshift GPIOB $18 + ! ;
: sda-lo 1 9 lshift 16 lshift GPIOB $18 + ! ;
: sda? ( -- f ) GPIOB $10 + @ 1 9 lshift and 0<> ;
: i2c-start sda-hi scl-hi sda-lo scl-lo ;
: i2c-stop sda-lo scl-hi sda-hi ;
: bit> ( f -- ) if sda-hi else sda-lo then scl-hi scl-lo ;
: byte> ( b -- ack ) 8 0 do dup $80 and bit> 2* loop drop  sda-hi scl-hi sda? 0= scl-lo ;
: <byte ( last -- b ) sda-hi 0 8 0 do 2* scl-hi sda? 1 and or scl-lo loop swap bit> ;
: cmd ( c -- ) i2c-start $3C 2* byte> drop 0 byte> drop byte> drop i2c-stop ;
: status ( -- b ) i2c-start $3C 2* 1+ byte> drop 1 <byte i2c-stop ;
bb-pins
i2c-start $3C 2* byte> . i2c-stop
i2c-start $3D 2* byte> . i2c-stop
status hex. 
$8D cmd $14 cmd $AF cmd $A5 cmd
status hex.
#!oled 1
