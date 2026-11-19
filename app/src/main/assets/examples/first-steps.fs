\ First steps with the STM32G431 in Solderless
\ Send this file (Send, then Whole file) and watch the Console.
\ Everything here reads and writes the chip's registers, exactly as on
\ a real G431. Addresses are from the reference manual RM0440.

\ Which chip is this? DBGMCU_IDCODE: device $468 is the G431/G441.
$E0042000 @ hex. decimal

\ Flash size in KiB, from the factory data in system memory
$1FFF75E0 h@ .

\ The chip's 96-bit unique ID
: uid. ( -- ) $1FFF7590 @ hex. $1FFF7594 @ hex. $1FFF7598 @ hex. decimal ;
uid.

: bit ( n -- mask ) 1 swap lshift ;

\ RCC decides where the clocks come from. After reset the CPU runs on
\ the internal 16 MHz oscillator, HSI16.
$40021000 constant RCC
RCC $08 + constant RCC_CFGR
: sysclk-source ( -- n ) RCC_CFGR @ 2 rshift 3 and ;   \ 1 HSI16, 2 HSE, 3 PLL
sysclk-source .

\ A peripheral needs its clock switched on in RCC before it works.
\ USART1's clock is off, so its registers read 0. Try it:
\   $40013800 @ hex.
\ and look for the chip message in the Console that says why.

\ SysTick, the core's timer: 16 million cycles are one second at 16 MHz.
$E000E010 constant STK_CTRL
$E000E014 constant STK_LOAD
$E000E018 constant STK_VAL
: wait-1s ( -- )
  16000000 1- STK_LOAD !  0 STK_VAL !
  %101 STK_CTRL !                       \ count the CPU clock, no interrupt
  begin STK_CTRL @ 16 bit and until     \ COUNTFLAG: counted down to 0
  0 STK_CTRL ! ;
: seconds ( n -- ) 0 ?do i . wait-1s loop ." done" ;
\ Try:  5 seconds

\ GPIO: PA5 as an output (no LED on the board yet; the breadboard comes
\ in a later version). Watch GPIOA in the Registers view while you try
\ these words.
$48000000 constant GPIOA
: pa5-output ( -- )
  0 bit RCC $4C + bis!                  \ GPIOA clock on (RCC_AHB2ENR)
  %11 10 lshift GPIOA bic!              \ MODER5 = 00 ...
  %01 10 lshift GPIOA bis! ;            \ ... then 01: output
: pa5-on  ( -- ) 5 bit GPIOA $18 + ! ;           \ BSRR: set
: pa5-off ( -- ) 5 bit 16 lshift GPIOA $18 + ! ; \ BSRR: reset
: pa5? ( -- f ) GPIOA $10 + @ 5 bit and 0<> ;    \ IDR
pa5-output pa5-on pa5? . pa5-off pa5? .

\ More: the libraries lib_pll (170 MHz), lib_systick (interrupts and
\ millisecond delays) and lib_rtc come with Mecrisp-Stellaris; open them
\ from Files.
