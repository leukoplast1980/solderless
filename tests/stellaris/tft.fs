\ A 240 x 240 ST7789 on SPI1: PA5 SCL, PA7 SDA, PA4 CS, PB0 DC, PB1 RES.
\ Pixels while asleep, a command too soon after a reset, the colour mode
\ after reset, then a proper start, colours, and rows the panel hides.
#!attach 1 tft PA4 PA5 PA7 PB0 PB1
$48000000 constant GPIOA  $48000400 constant GPIOB  $40013000 constant SPI1
: ms ( n -- ) 15999 $E000E014 !  0 $E000E018 !  5 $E000E010 !
  0 ?do begin $E000E010 @ $10000 and until loop  0 $E000E010 ! ;
: pins ( -- )
  %11 $4002104C bis!  1 12 lshift $40021060 bis!
  1 4 lshift GPIOA $18 + !  %11 GPIOB $18 + !                     \ CS, DC, RES high
  $FF 8 lshift GPIOA bic!  %10001001 8 lshift GPIOA bis!          \ PA4 out, PA5 PA7 AF
  $F0F 20 lshift GPIOA $20 + bic!  $505 20 lshift GPIOA $20 + bis!
  $F GPIOB bic!  %0101 GPIOB bis!                                  \ PB0 PB1 out
  $1700 SPI1 4 + !  $34C SPI1 ! ;                                  \ master, 4 MHz
: idle ( -- ) begin SPI1 8 + @ $80 and 0= until ;                 \ BSY clear: the byte is out
: >spi ( b -- ) begin SPI1 8 + @ 2 and until  SPI1 $C + c! ;
: cs-lo 1 4 lshift 16 lshift GPIOA $18 + ! ;  : cs-hi idle 1 4 lshift GPIOA $18 + ! ;
: dc-cmd idle 1 16 lshift GPIOB $18 + ! ;  : dc-data idle 1 GPIOB $18 + ! ;
: cmd ( c -- ) cs-lo dc-cmd >spi cs-hi ;
: data ( b -- ) cs-lo dc-data >spi cs-hi ;
: 2data ( u -- ) dup 8 rshift data data ;
: window ( x0 y0 x1 y1 -- ) >r >r $2A cmd swap 2data r> 2data $2B cmd 2data r> 2data ;
0 variable colour
: fill ( x0 y0 x1 y1 c -- ) colour ! 2over 2over window  $2C cmd
  cs-lo dc-data  rot - 1+ -rot swap - 1+ *  0 ?do colour @ dup 8 rshift >spi >spi loop  cs-hi ;
pins
0 0 7 7 $F800 fill
#!tft 1

: hwreset ( -- ) 2 16 lshift GPIOB $18 + !  1 ms  2 GPIOB $18 + ! ;
hwreset $11 cmd
#!tft 1
: start ( -- ) hwreset 120 ms  $11 cmd 5 ms  $3A cmd $55 data  $36 cmd 0 data  $21 cmd  $29 cmd ;
start
0 0 239 239 0 fill  0 0 119 119 $F800 fill  120 0 239 119 $07E0 fill  0 120 119 239 $001F fill  120 120 239 239 $FFFF fill
#!tft 1
$36 cmd $C0 data  0 0 15 15 $FFE0 fill
