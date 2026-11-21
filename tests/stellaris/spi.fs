\ A W25Q64 on SPI1: PA5 CLK, PA6 DO, PA7 DI, PA4 CS (a GPIO). IDs, write
\ enable, page program, read back, erase, and the flash's own rules.
#!attach 1 flash PA4 PA5 PA7 PA6
$48000000 constant GPIOA
$40013000 constant SPI1
: spi-init ( -- )
  1 $4002104C bis!  1 12 lshift $40021060 bis!           \ GPIOA and SPI1 clocks
  1 4 lshift GPIOA $18 + !                                \ CS high before it is an output
  $FF 8 lshift GPIOA bic!  %10101001 8 lshift GPIOA bis!  \ PA4 output, PA5-7 alternate function
  $FFF 20 lshift GPIOA $20 + bic!  $555 20 lshift GPIOA $20 + bis!   \ AF5: SPI1
  $1700 SPI1 4 + !                                        \ 8-bit frames, RXNE at one byte (FRXTH)
  $354 SPI1 ! ;                                           \ master, PCLK/8, SSM and SSI, on
: >spi> ( b -- b )
  begin SPI1 8 + @ 2 and until  SPI1 $C + c!
  begin SPI1 8 + @ 1 and until  SPI1 $C + c@ ;
: cs-lo 1 4 lshift 16 lshift GPIOA $18 + ! ;
: cs-hi 1 4 lshift GPIOA $18 + ! ;
: jedec ( -- ) cs-lo $9F >spi> drop 0 >spi> hex. 0 >spi> hex. 0 >spi> hex. cs-hi ;
: status ( -- sr ) cs-lo 5 >spi> drop 0 >spi> cs-hi ;
: wren ( -- ) cs-lo 6 >spi> drop cs-hi ;
: wait ( -- ) begin status 1 and 0= until ;
: >addr ( a -- ) dup 16 rshift >spi> drop dup 8 rshift >spi> drop >spi> drop ;
: prog ( c-addr u a -- ) cs-lo 2 >spi> drop >addr 0 ?do dup i + c@ >spi> drop loop drop cs-hi ;
: .c ( c -- ) dup 32 u< over 126 u> or if drop [char] . then emit ;
: rd ( a u -- ) cs-lo 3 >spi> drop swap >addr 0 ?do 0 >spi> .c loop cs-hi ;
: erase4k ( a -- ) wren cs-lo $20 >spi> drop >addr cs-hi ;
: hello s" Hello from Forth" ;
: world s" World" ;
spi-init jedec
status hex.
hello 0 prog  0 16 rd
wren status hex.
hello 0 prog  status hex. wait status hex.
0 16 rd
wren world 0 prog wait  0 16 rd
0 erase4k status hex.  0 4 rd
wait 0 4 rd
#!flash 1 0 16
