\ The W25Q on bit-banged SPI (mode 0): PB13 CLK, PB15 DI, PB14 DO, PB12 CS.
\ The decoder on the pins shifts the bits in on SCK rising and drives DO.
#!attach 1 flash PB12 PB13 PB15 PB14
$48000400 constant GPIOB
: bb-pins ( -- )
  %10 $4002104C bis!
  %1011 12 lshift GPIOB $18 + !  1 13 lshift 16 lshift GPIOB $18 + !   \ CS and DI high, CLK low
  $FF 24 lshift GPIOB bic!  %01000101 24 lshift GPIOB bis! ;          \ PB12, 13, 15 outputs; PB14 input
: pin! ( f n -- ) 1 swap lshift swap 0= if 16 lshift then GPIOB $18 + ! ;
: do? ( -- f ) GPIOB $10 + @ 1 14 lshift and 0<> ;
: bb ( b -- b ) 0 swap 8 0 do dup $80 and 15 pin!  1 13 pin!  swap 2* do? 1 and or swap  0 13 pin!  2* loop drop ;
: jedec ( -- ) 0 12 pin! $9F bb drop 0 bb hex. 0 bb hex. 0 bb hex. 1 12 pin! ;
: uid ( -- ) 0 12 pin! $4B bb drop 0 bb 0 bb 0 bb 0 bb 2drop 2drop 8 0 do 0 bb hex. loop 1 12 pin! ;
#!poke 1 0x100 536F6C6465726C657373
: rd ( a u -- ) 0 12 pin! 3 bb drop over 16 rshift bb drop over 8 rshift bb drop swap bb drop 0 ?do 0 bb emit loop 1 12 pin! ;
bb-pins jedec
uid
$100 10 rd
