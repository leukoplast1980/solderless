\ The sample code the Board tab gives for LEDs, as generated (SamplesTest
\ checks that it still matches): GPIO, both polarities, and timer PWM.
#!attach 1 led PB5 r=270 vf=1900
#!attach 2 led PB6 r=270 vf=2100 vcc
#!attach 3 led PC6 r=270 vf=1900
#!attach 4 led PA8 r=270 vf=1900 vcc

\ led: red LED, lit while PB5 is high
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB5 (pin 58) → 270 Ω → red LED → GND
$48000400 constant led-port  \ GPIOB
5 constant led-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: led-on ( -- )
  1 led-pin lshift led-port $18 + ! ;            \ BSRR: set, the pin goes high
: led-off ( -- )
  1 led-pin lshift 16 lshift led-port $18 + ! ;  \ BSRR: reset, the pin goes low
: led-toggle ( -- )
  1 led-pin lshift led-port $14 + xor! ;         \ ODR: flip the pin
: led-init ( -- )
  1 1 lshift $4002104C bis!                      \ RCC AHB2ENR: clock for GPIOB
  led-off                                        \ dark from the start
  %11 led-pin 2* lshift led-port bic!            \ MODER: clear the two bits
  %01 led-pin 2* lshift led-port bis!            \ MODER: 01 = output
  1 led-pin lshift led-port 4 + bic! ;           \ OTYPER: push-pull

\ Blink twice a second until a key is pressed
: led-blink ( -- )
  led-init begin led-toggle 250 ms key? until led-off ;

\ Try it: led-init led-on   or   led-blink (Enter stops it)

\ led2: green LED, lit while PB6 is low
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ 3V3 → green LED → 220 Ω → PB6 (pin 59)
$48000400 constant led2-port  \ GPIOB
6 constant led2-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: led2-on ( -- )
  1 led2-pin lshift 16 lshift led2-port $18 + ! ; \ BSRR: reset, the pin goes low
: led2-off ( -- )
  1 led2-pin lshift led2-port $18 + ! ;           \ BSRR: set, the pin goes high
: led2-toggle ( -- )
  1 led2-pin lshift led2-port $14 + xor! ;        \ ODR: flip the pin
: led2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  led2-off                                        \ dark from the start
  %11 led2-pin 2* lshift led2-port bic!           \ MODER: clear the two bits
  %01 led2-pin 2* lshift led2-port bis!           \ MODER: 01 = output
  1 led2-pin lshift led2-port 4 + bic! ;          \ OTYPER: push-pull

\ Blink twice a second until a key is pressed
: led2-blink ( -- )
  led2-init begin led2-toggle 250 ms key? until led2-off ;

\ Try it: led2-init led2-on   or   led2-blink (Enter stops it)

\ led3: red LED, lit while PC6 is high, dimmed by TIM3 CH1
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PC6 (pin 38) → 270 Ω → red LED → GND
$48000800 constant led3-port  \ GPIOC
6 constant led3-pin
$40000400 constant led3-timer  \ TIM3
1 constant led3-ch
2 constant led3-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: led3-init ( -- )
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  %11 led3-pin 2* lshift led3-port bic!           \ MODER: clear the two bits
  %10 led3-pin 2* lshift led3-port bis!           \ MODER: 10 = alternate function
  1 led3-pin lshift led3-port 4 + bic!            \ OTYPER: push-pull
  $F led3-pin 4 * lshift led3-port $20 + bic!     \ AFRL: clear the four bits
  led3-af led3-pin 4 * lshift led3-port $20 + bis! \ AFRL: AF2 = TIM3 CH1
  1 1 lshift $40021058 bis!                       \ RCC APB1ENR1: clock for TIM3
  15 led3-timer $28 + !                           \ PSC: 16 MHz / 16, a tick every microsecond
  999 led3-timer $2C + !                          \ ARR: 1000 ticks a period, 1 kHz
  $FF led3-timer $18 + bic!                       \ CCMR1: clear channel 1
  $68 led3-timer $18 + bis!                       \ CCMR1: channel 1 in PWM mode 1, CCR1 preloaded
  $F led3-timer $20 + bic!                        \ CCER: clear channel 1
  $1 led3-timer $20 + bis!                        \ CCER: CC1E, output on
  0 led3-timer $34 + !                            \ CCR1: 0, off
  1 led3-timer $14 + !                            \ EGR: UG, load PSC and ARR now
  $81 led3-timer bis! ;                           \ CR1: ARPE and CEN, the timer runs
: led3-bright ( percent -- )                      \ 0 = off, 100 = full
  10 * led3-timer $34 + ! ;                       \ CCR1: lit for this many of the 1000 ticks
: led3-on ( -- ) 100 led3-bright ;
: led3-off ( -- ) 0 led3-bright ;

\ Fade up and down until a key is pressed
: led3-fade ( -- )
  led3-init
  begin
    101 0 do i led3-bright 10 ms loop
    0 100 do i led3-bright 10 ms -1 +loop
  key? until led3-off ;

\ Try it: led3-init 30 led3-bright   or   led3-fade (Enter stops it)

\ led4: red LED, lit while PA8 is low, dimmed by TIM1 CH1
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ 3V3 → red LED → 270 Ω → PA8 (pin 42)
$48000000 constant led4-port  \ GPIOA
8 constant led4-pin
$40012C00 constant led4-timer  \ TIM1
1 constant led4-ch
6 constant led4-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: led4-init ( -- )
  1 0 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOA
  %11 led4-pin 2* lshift led4-port bic!           \ MODER: clear the two bits
  %10 led4-pin 2* lshift led4-port bis!           \ MODER: 10 = alternate function
  1 led4-pin lshift led4-port 4 + bic!            \ OTYPER: push-pull
  $F led4-pin 8 - 4 * lshift led4-port $24 + bic! \ AFRH: clear the four bits
  led4-af led4-pin 8 - 4 * lshift led4-port $24 + bis! \ AFRH: AF6 = TIM1 CH1
  1 11 lshift $40021060 bis!                      \ RCC APB2ENR: clock for TIM1
  15 led4-timer $28 + !                           \ PSC: 16 MHz / 16, a tick every microsecond
  999 led4-timer $2C + !                          \ ARR: 1000 ticks a period, 1 kHz
  $FF led4-timer $18 + bic!                       \ CCMR1: clear channel 1
  $68 led4-timer $18 + bis!                       \ CCMR1: channel 1 in PWM mode 1, CCR1 preloaded
  $F led4-timer $20 + bic!                        \ CCER: clear channel 1
  $3 led4-timer $20 + bis!                        \ CCER: CC1E, output on; CC1P, inverted: the LED lights while the pin is low
  0 led4-timer $34 + !                            \ CCR1: 0, off
  $8000 led4-timer $44 + bis!                     \ BDTR: MOE, outputs on (TIM1 needs it)
  1 led4-timer $14 + !                            \ EGR: UG, load PSC and ARR now
  $81 led4-timer bis! ;                           \ CR1: ARPE and CEN, the timer runs
: led4-bright ( percent -- )                      \ 0 = off, 100 = full
  10 * led4-timer $34 + ! ;                       \ CCR1: lit for this many of the 1000 ticks
: led4-on ( -- ) 100 led4-bright ;
: led4-off ( -- ) 0 led4-bright ;

\ Fade up and down until a key is pressed
: led4-fade ( -- )
  led4-init
  begin
    101 0 do i led4-bright 10 ms loop
    0 100 do i led4-bright 10 ms -1 +loop
  key? until led4-off ;

\ Try it: led4-init 30 led4-bright   or   led4-fade (Enter stops it)

led-init led-on  led2-init led2-on
led3-init 25 led3-bright  led4-init 75 led4-bright
10 ms
#!pins
led-toggle  led2-off  led4-off  led3-on
10 ms
#!pins
