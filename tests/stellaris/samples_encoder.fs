\ The sample code the Board tab gives for rotary encoders, as generated
\ (SamplesTest checks that it still matches): TIM2 on PA0/PA1, TIM4 on PB6/PB7.
#!attach 1 encoder PA0 PA1 PC3
#!attach 2 encoder PB6 PB7 PC2

\ enc: rotary encoder, KY-040: CLK PA0, DT PA1 on TIM2 in encoder mode, SW PC3
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PA0 (pin 12) → encoder CLK (A); + → 3V3, GND → GND; the module pulls CLK and DT up (10 kΩ)
$48000000 constant enc-clk-port  \ GPIOA
0 constant enc-clk-pin
$40000000 constant enc-timer  \ TIM2
1 constant enc-clk-ch
1 constant enc-clk-af
\ PA1 (pin 13) → encoder DT (B)
$48000000 constant enc-dt-port  \ GPIOA
1 constant enc-dt-pin
2 constant enc-dt-ch
1 constant enc-dt-af
\ PC3 (pin 11) → encoder SW, the push switch to GND: turn on the pin's pull-up
$48000800 constant enc-sw-port  \ GPIOC
3 constant enc-sw-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: enc-count ( -- n )                     \ the counter, signed: up one way, down the other
  enc-timer $24 + @ ;                    \ TIM2 counts in 32 bits
: enc-steps ( -- n ) enc-count 4 / ;     \ detents: four edges each
: enc-pressed? ( -- flag )               \ the push switch, low while pressed
  enc-sw-port $10 + @ 1 enc-sw-pin lshift and 0= ;
: enc-zero ( -- ) 0 enc-timer $24 + ! ;  \ count from here

: enc-init ( -- )
  1 0 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOA
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  1 0 lshift $40021058 bis!                       \ RCC APB1ENR1: clock for TIM2
  %11 enc-clk-pin 2* lshift enc-clk-port bic!     \ MODER: clear the two bits
  %10 enc-clk-pin 2* lshift enc-clk-port bis!     \ MODER: 10 = alternate function
  $F enc-clk-pin 4 * lshift enc-clk-port $20 + bic! \ AFRL: clear the four bits
  enc-clk-af enc-clk-pin 4 * lshift enc-clk-port $20 + bis! \ AFRL: AF1 = TIM2 CH1
  %11 enc-dt-pin 2* lshift enc-dt-port bic!       \ MODER: clear the two bits
  %10 enc-dt-pin 2* lshift enc-dt-port bis!       \ MODER: 10 = alternate function
  $F enc-dt-pin 4 * lshift enc-dt-port $20 + bic! \ AFRL: clear the four bits
  enc-dt-af enc-dt-pin 4 * lshift enc-dt-port $20 + bis! \ AFRL: AF1 = TIM2 CH2
  %11 enc-sw-pin 2* lshift enc-sw-port bic!       \ MODER: 00 = input
  %11 enc-sw-pin 2* lshift enc-sw-port $0C + bic! \ PUPDR: clear the two bits
  %01 enc-sw-pin 2* lshift enc-sw-port $0C + bis! \ PUPDR: 01 = pull-up; the module has none for SW
  0 enc-timer !                                   \ CR1: stopped while setting it up
  3 enc-timer 8 + !                               \ SMCR: encoder mode 3, both inputs' edges: 4 a detent
  $0101 enc-timer $18 + !                         \ CCMR1: CC1S and CC2S = 01, CH1 and CH2 inputs from their pins
  0 enc-timer $20 + !                             \ CCER: neither input inverted
  -1 enc-timer $2C + !                            \ ARR: count over the whole range, then wrap
  0 enc-timer $24 + !                             \ CNT: 0
  1 enc-timer ! ;                                 \ CR1: CEN, on

\ Each detent as it is turned, and the push, until a key is pressed
: enc-demo ( -- )
  enc-init 0
  begin
    enc-steps 2dup <> if dup . then nip
    enc-pressed? if ." push " begin 20 ms enc-pressed? 0= until then
    20 ms key?
  until drop ;

\ Try it: enc-init enc-steps .   or   enc-demo (turn it; Enter stops it)

\ enc2: rotary encoder, KY-040: CLK PB6, DT PB7 on TIM4 in encoder mode, SW PC2
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB6 (pin 59) → encoder CLK (A); + → 3V3, GND → GND; the module pulls CLK and DT up (10 kΩ)
$48000400 constant enc2-clk-port  \ GPIOB
6 constant enc2-clk-pin
$40000800 constant enc2-timer  \ TIM4
1 constant enc2-clk-ch
2 constant enc2-clk-af
\ PB7 (pin 60) → encoder DT (B)
$48000400 constant enc2-dt-port  \ GPIOB
7 constant enc2-dt-pin
2 constant enc2-dt-ch
2 constant enc2-dt-af
\ PC2 (pin 10) → encoder SW, the push switch to GND: turn on the pin's pull-up
$48000800 constant enc2-sw-port  \ GPIOC
2 constant enc2-sw-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: enc2-count ( -- n )                        \ the counter, signed: up one way, down the other
  enc2-timer $24 + @ 16 lshift 16 arshift ;  \ 16 bits, sign-extended
: enc2-steps ( -- n ) enc2-count 4 / ;       \ detents: four edges each
: enc2-pressed? ( -- flag )                  \ the push switch, low while pressed
  enc2-sw-port $10 + @ 1 enc2-sw-pin lshift and 0= ;
: enc2-zero ( -- ) 0 enc2-timer $24 + ! ;    \ count from here

: enc2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  1 2 lshift $40021058 bis!                       \ RCC APB1ENR1: clock for TIM4
  %11 enc2-clk-pin 2* lshift enc2-clk-port bic!   \ MODER: clear the two bits
  %10 enc2-clk-pin 2* lshift enc2-clk-port bis!   \ MODER: 10 = alternate function
  $F enc2-clk-pin 4 * lshift enc2-clk-port $20 + bic! \ AFRL: clear the four bits
  enc2-clk-af enc2-clk-pin 4 * lshift enc2-clk-port $20 + bis! \ AFRL: AF2 = TIM4 CH1
  %11 enc2-dt-pin 2* lshift enc2-dt-port bic!     \ MODER: clear the two bits
  %10 enc2-dt-pin 2* lshift enc2-dt-port bis!     \ MODER: 10 = alternate function
  $F enc2-dt-pin 4 * lshift enc2-dt-port $20 + bic! \ AFRL: clear the four bits
  enc2-dt-af enc2-dt-pin 4 * lshift enc2-dt-port $20 + bis! \ AFRL: AF2 = TIM4 CH2
  %11 enc2-sw-pin 2* lshift enc2-sw-port bic!     \ MODER: 00 = input
  %11 enc2-sw-pin 2* lshift enc2-sw-port $0C + bic! \ PUPDR: clear the two bits
  %01 enc2-sw-pin 2* lshift enc2-sw-port $0C + bis! \ PUPDR: 01 = pull-up; the module has none for SW
  0 enc2-timer !                                  \ CR1: stopped while setting it up
  3 enc2-timer 8 + !                              \ SMCR: encoder mode 3, both inputs' edges: 4 a detent
  $0101 enc2-timer $18 + !                        \ CCMR1: CC1S and CC2S = 01, CH1 and CH2 inputs from their pins
  0 enc2-timer $20 + !                            \ CCER: neither input inverted
  $FFFF enc2-timer $2C + !                        \ ARR: count over the whole range, then wrap
  0 enc2-timer $24 + !                            \ CNT: 0
  1 enc2-timer ! ;                                \ CR1: CEN, on

\ Each detent as it is turned, and the push, until a key is pressed
: enc2-demo ( -- )
  enc2-init 0
  begin
    enc2-steps 2dup <> if dup . then nip
    enc2-pressed? if ." push " begin 20 ms enc2-pressed? 0= until then
    20 ms key?
  until drop ;

\ Try it: enc2-init enc2-steps .   or   enc2-demo (turn it; Enter stops it)

enc-init enc2-init enc-steps . enc2-steps .
#!turn 1 3
#!turn 2 -2
200 ms enc-steps . enc2-steps . enc2-count .
#!turn 1 -4
200 ms enc-steps . enc-count .
enc-pressed? .
#!input 1 1
20 ms enc-pressed? .
#!input 1 0
20 ms enc-pressed? .
