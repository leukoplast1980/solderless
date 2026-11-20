\ SPI mistakes: no SSM/SSI (mode fault), RXNE that waits for two bytes
\ (FRXTH clear), a word write that sends two frames, mode 1 where the
\ flash wants 0 or 3 (3 works), the flash still busy.
#!attach 1 flash PA4 PA5 PA7 PA6
$48000000 constant GPIOA
$40013000 constant SPI1
: pins ( -- )
  1 $4002104C bis!  1 12 lshift $40021060 bis!
  1 4 lshift GPIOA $18 + !
  $FF 8 lshift GPIOA bic!  %10101001 8 lshift GPIOA bis!
  $FFF 20 lshift GPIOA $20 + bic!  $555 20 lshift GPIOA $20 + bis! ;
: sr. ( -- ) SPI1 8 + @ hex. ;
: cs-lo 1 4 lshift 16 lshift GPIOA $18 + ! ;
: cs-hi 1 4 lshift GPIOA $18 + ! ;
: settle 2000 0 do loop ;
pins
$54 SPI1 !  SPI1 @ hex. sr.
#!clear
#!attach 1 flash PA4 PA5 PA7 PA6
0 SPI1 !  $354 SPI1 !  SPI1 @ hex.
cs-lo $9F SPI1 $C + c! settle sr.
0 SPI1 $C + c! settle sr.
SPI1 $C + h@ hex. cs-hi sr.
$1700 SPI1 4 + !
cs-lo $9F SPI1 $C + ! settle sr. SPI1 $C + c@ hex. SPI1 $C + c@ hex. cs-hi
0 SPI1 !  $355 SPI1 !
cs-lo $9F SPI1 $C + c! settle SPI1 $C + c@ drop 0 SPI1 $C + c! settle SPI1 $C + c@ hex. cs-hi
0 SPI1 !  $357 SPI1 !
cs-lo $9F SPI1 $C + c! settle SPI1 $C + c@ drop 0 SPI1 $C + c! settle SPI1 $C + c@ hex. cs-hi
0 SPI1 !  $354 SPI1 !
: b ( x -- y ) SPI1 $C + c! settle SPI1 $C + c@ ;
cs-lo 6 b drop cs-hi  cs-lo $20 b drop 0 b drop 0 b drop 0 b drop cs-hi
cs-lo $9F b drop 0 b hex. cs-hi
cs-lo 5 b drop 0 b hex. cs-hi
