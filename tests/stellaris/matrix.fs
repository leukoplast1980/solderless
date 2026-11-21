\ A 4-in-1 MAX7219 matrix on SPI3 (PC10 CLK, PC12 DIN, PC9 CS), 16-bit
\ frames: the first word sent goes to the chip farthest from DIN. At power
\ on the digit registers hold noise, which shows once shutdown is off.
#!attach 1 matrix PC9 PC10 PC12 chips=4
$48000800 constant GPIOC
$40003C00 constant SPI3
: pins ( -- )
  %100 $4002104C bis!  1 15 lshift $40021058 bis!         \ GPIOC, SPI3
  1 9 lshift GPIOC $18 + !                                \ CS high
  %11 18 lshift GPIOC bic!  %01 18 lshift GPIOC bis!      \ PC9 output
  %11 20 lshift %11 24 lshift or GPIOC bic!  %10 20 lshift %10 24 lshift or GPIOC bis!
  $F 8 lshift $F 16 lshift or GPIOC $24 + bic!  6 8 lshift 6 16 lshift or GPIOC $24 + bis! ;  \ AF6
: spi-on ( -- ) $0F00 SPI3 4 + !  $354 SPI3 ! ;            \ 16-bit frames, master, /8
: >16 ( u -- ) begin SPI3 8 + @ 2 and until  SPI3 $C + h!  begin SPI3 8 + @ $80 and 0= until ;
: cs-lo 1 9 lshift 16 lshift GPIOC $18 + ! ;
: cs-hi 1 9 lshift GPIOC $18 + ! ;
: all ( reg v -- ) swap 8 lshift or  cs-lo 4 0 do dup >16 loop drop cs-hi ;
pins spi-on
#!matrix 1
$C 1 all  $B 7 all  $A 3 all  9 0 all
#!matrix 1
: clear ( -- ) 9 1 do i 0 all loop ;
clear
#!matrix 1
: arrow ( -- ) $18 $3C $7E $FF $18 $18 $18 $18  8 0 do  cs-lo 3 0 do 0 >16 loop  8 i - 8 lshift or >16 cs-hi loop ;
arrow
#!matrix 1
$C 0 all
#!matrix 1
