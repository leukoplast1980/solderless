\ The sample code the Board tab gives for RGB LEDs, as generated (SamplesTest
\ checks that it still matches): TIM1 (needs MOE), TIM3 inverted, and GPIO.
#!attach 1 led PA8 r=270 vf=1900
#!attach 2 led PA9 r=56 vf=3000
#!attach 3 led PA10 r=56 vf=3000
#!attach 4 led PC6 r=270 vf=1900 vcc
#!attach 5 led PC7 r=56 vf=3000 vcc
#!attach 6 led PC8 r=56 vf=3000 vcc
#!attach 7 led PB10 r=270 vf=1900
#!attach 8 led PB11 r=56 vf=3000
#!attach 9 led PB12 r=56 vf=3000

\ rgb: RGB LED, common cathode: red on PA8, green on PA9, blue on PA10
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PA8 (pin 42) → 270 Ω → red LED → GND
$48000000 constant rgb-red-port  \ GPIOA
8 constant rgb-red-pin
$40012C00 constant rgb-timer  \ TIM1
1 constant rgb-red-ch
6 constant rgb-red-af
\ PA9 (pin 43) → 56 Ω → green LED → GND
$48000000 constant rgb-green-port  \ GPIOA
9 constant rgb-green-pin
2 constant rgb-green-ch
6 constant rgb-green-af
\ PA10 (pin 44) → 56 Ω → blue LED → GND
$48000000 constant rgb-blue-port  \ GPIOA
10 constant rgb-blue-pin
3 constant rgb-blue-ch
6 constant rgb-blue-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: rgb-init ( -- )
  1 0 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOA
  %11 rgb-red-pin 2* lshift rgb-red-port bic!     \ MODER: clear the two bits
  %10 rgb-red-pin 2* lshift rgb-red-port bis!     \ MODER: 10 = alternate function
  1 rgb-red-pin lshift rgb-red-port 4 + bic!      \ OTYPER: push-pull
  $F rgb-red-pin 8 - 4 * lshift rgb-red-port $24 + bic! \ AFRH: clear the four bits
  rgb-red-af rgb-red-pin 8 - 4 * lshift rgb-red-port $24 + bis! \ AFRH: AF6 = TIM1 CH1
  %11 rgb-green-pin 2* lshift rgb-green-port bic! \ MODER: clear the two bits
  %10 rgb-green-pin 2* lshift rgb-green-port bis! \ MODER: 10 = alternate function
  1 rgb-green-pin lshift rgb-green-port 4 + bic!  \ OTYPER: push-pull
  $F rgb-green-pin 8 - 4 * lshift rgb-green-port $24 + bic! \ AFRH: clear the four bits
  rgb-green-af rgb-green-pin 8 - 4 * lshift rgb-green-port $24 + bis! \ AFRH: AF6 = TIM1 CH2
  %11 rgb-blue-pin 2* lshift rgb-blue-port bic!   \ MODER: clear the two bits
  %10 rgb-blue-pin 2* lshift rgb-blue-port bis!   \ MODER: 10 = alternate function
  1 rgb-blue-pin lshift rgb-blue-port 4 + bic!    \ OTYPER: push-pull
  $F rgb-blue-pin 8 - 4 * lshift rgb-blue-port $24 + bic! \ AFRH: clear the four bits
  rgb-blue-af rgb-blue-pin 8 - 4 * lshift rgb-blue-port $24 + bis! \ AFRH: AF6 = TIM1 CH3
  1 11 lshift $40021060 bis!                      \ RCC APB2ENR: clock for TIM1
  15 rgb-timer $28 + !                            \ PSC: 16 MHz / 16, a tick every microsecond
  999 rgb-timer $2C + !                           \ ARR: 1000 ticks a period, 1 kHz
  $FF rgb-timer $18 + bic!                        \ CCMR1: clear channel 1
  $68 rgb-timer $18 + bis!                        \ CCMR1: channel 1 in PWM mode 1, CCR1 preloaded
  $F rgb-timer $20 + bic!                         \ CCER: clear channel 1
  $1 rgb-timer $20 + bis!                         \ CCER: CC1E, output on
  0 rgb-timer $34 + !                             \ CCR1: red off
  $FF00 rgb-timer $18 + bic!                      \ CCMR1: clear channel 2
  $6800 rgb-timer $18 + bis!                      \ CCMR1: channel 2 in PWM mode 1, CCR2 preloaded
  $F0 rgb-timer $20 + bic!                        \ CCER: clear channel 2
  $10 rgb-timer $20 + bis!                        \ CCER: CC2E, output on
  0 rgb-timer $38 + !                             \ CCR2: green off
  $FF rgb-timer $1C + bic!                        \ CCMR2: clear channel 3
  $68 rgb-timer $1C + bis!                        \ CCMR2: channel 3 in PWM mode 1, CCR3 preloaded
  $F00 rgb-timer $20 + bic!                       \ CCER: clear channel 3
  $100 rgb-timer $20 + bis!                       \ CCER: CC3E, output on
  0 rgb-timer $3C + !                             \ CCR3: blue off
  $8000 rgb-timer $44 + bis!                      \ BDTR: MOE, outputs on (TIM1 needs it)
  1 rgb-timer $14 + !                             \ EGR: UG, load PSC and ARR now
  $81 rgb-timer bis! ;                            \ CR1: ARPE and CEN, the timer runs
: rgb-colour ( red green blue -- )                \ each 0..100 %
  10 * rgb-timer $3C + !                          \ CCR3: blue
  10 * rgb-timer $38 + !                          \ CCR2: green
  10 * rgb-timer $34 + ! ;                        \ CCR1: red
: rgb-off ( -- ) 0 0 0 rgb-colour ;

\ Fade red, green, blue, red until a key is pressed
: rgb-rainbow ( -- )
  rgb-init
  begin
    100 0 do 100 i -  i  0  rgb-colour 20 ms loop \ red to green
    100 0 do 0  100 i -  i  rgb-colour 20 ms loop \ green to blue
    100 0 do i  0  100 i -  rgb-colour 20 ms loop \ blue to red
  key? until rgb-off ;

\ Try it: rgb-init 100 40 0 rgb-colour (orange)   or   rgb-rainbow (Enter stops it)

\ rgb2: RGB LED, common anode: red on PC6, green on PC7, blue on PC8
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ 3V3 → red LED → 270 Ω → PC6 (pin 38)
$48000800 constant rgb2-red-port  \ GPIOC
6 constant rgb2-red-pin
$40000400 constant rgb2-timer  \ TIM3
1 constant rgb2-red-ch
2 constant rgb2-red-af
\ 3V3 → green LED → 56 Ω → PC7 (pin 39)
$48000800 constant rgb2-green-port  \ GPIOC
7 constant rgb2-green-pin
2 constant rgb2-green-ch
2 constant rgb2-green-af
\ 3V3 → blue LED → 56 Ω → PC8 (pin 40)
$48000800 constant rgb2-blue-port  \ GPIOC
8 constant rgb2-blue-pin
3 constant rgb2-blue-ch
2 constant rgb2-blue-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: rgb2-init ( -- )
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  %11 rgb2-red-pin 2* lshift rgb2-red-port bic!   \ MODER: clear the two bits
  %10 rgb2-red-pin 2* lshift rgb2-red-port bis!   \ MODER: 10 = alternate function
  1 rgb2-red-pin lshift rgb2-red-port 4 + bic!    \ OTYPER: push-pull
  $F rgb2-red-pin 4 * lshift rgb2-red-port $20 + bic! \ AFRL: clear the four bits
  rgb2-red-af rgb2-red-pin 4 * lshift rgb2-red-port $20 + bis! \ AFRL: AF2 = TIM3 CH1
  %11 rgb2-green-pin 2* lshift rgb2-green-port bic! \ MODER: clear the two bits
  %10 rgb2-green-pin 2* lshift rgb2-green-port bis! \ MODER: 10 = alternate function
  1 rgb2-green-pin lshift rgb2-green-port 4 + bic! \ OTYPER: push-pull
  $F rgb2-green-pin 4 * lshift rgb2-green-port $20 + bic! \ AFRL: clear the four bits
  rgb2-green-af rgb2-green-pin 4 * lshift rgb2-green-port $20 + bis! \ AFRL: AF2 = TIM3 CH2
  %11 rgb2-blue-pin 2* lshift rgb2-blue-port bic! \ MODER: clear the two bits
  %10 rgb2-blue-pin 2* lshift rgb2-blue-port bis! \ MODER: 10 = alternate function
  1 rgb2-blue-pin lshift rgb2-blue-port 4 + bic!  \ OTYPER: push-pull
  $F rgb2-blue-pin 8 - 4 * lshift rgb2-blue-port $24 + bic! \ AFRH: clear the four bits
  rgb2-blue-af rgb2-blue-pin 8 - 4 * lshift rgb2-blue-port $24 + bis! \ AFRH: AF2 = TIM3 CH3
  1 1 lshift $40021058 bis!                       \ RCC APB1ENR1: clock for TIM3
  15 rgb2-timer $28 + !                           \ PSC: 16 MHz / 16, a tick every microsecond
  999 rgb2-timer $2C + !                          \ ARR: 1000 ticks a period, 1 kHz
  $FF rgb2-timer $18 + bic!                       \ CCMR1: clear channel 1
  $68 rgb2-timer $18 + bis!                       \ CCMR1: channel 1 in PWM mode 1, CCR1 preloaded
  $F rgb2-timer $20 + bic!                        \ CCER: clear channel 1
  $3 rgb2-timer $20 + bis!                        \ CCER: CC1E, output on; CC1P, inverted: common anode, a colour lights while its pin is low
  0 rgb2-timer $34 + !                            \ CCR1: red off
  $FF00 rgb2-timer $18 + bic!                     \ CCMR1: clear channel 2
  $6800 rgb2-timer $18 + bis!                     \ CCMR1: channel 2 in PWM mode 1, CCR2 preloaded
  $F0 rgb2-timer $20 + bic!                       \ CCER: clear channel 2
  $30 rgb2-timer $20 + bis!                       \ CCER: CC2E, output on; CC2P, inverted
  0 rgb2-timer $38 + !                            \ CCR2: green off
  $FF rgb2-timer $1C + bic!                       \ CCMR2: clear channel 3
  $68 rgb2-timer $1C + bis!                       \ CCMR2: channel 3 in PWM mode 1, CCR3 preloaded
  $F00 rgb2-timer $20 + bic!                      \ CCER: clear channel 3
  $300 rgb2-timer $20 + bis!                      \ CCER: CC3E, output on; CC3P, inverted
  0 rgb2-timer $3C + !                            \ CCR3: blue off
  1 rgb2-timer $14 + !                            \ EGR: UG, load PSC and ARR now
  $81 rgb2-timer bis! ;                           \ CR1: ARPE and CEN, the timer runs
: rgb2-colour ( red green blue -- )               \ each 0..100 %
  10 * rgb2-timer $3C + !                         \ CCR3: blue
  10 * rgb2-timer $38 + !                         \ CCR2: green
  10 * rgb2-timer $34 + ! ;                       \ CCR1: red
: rgb2-off ( -- ) 0 0 0 rgb2-colour ;

\ Fade red, green, blue, red until a key is pressed
: rgb2-rainbow ( -- )
  rgb2-init
  begin
    100 0 do 100 i -  i  0  rgb2-colour 20 ms loop \ red to green
    100 0 do 0  100 i -  i  rgb2-colour 20 ms loop \ green to blue
    100 0 do i  0  100 i -  rgb2-colour 20 ms loop \ blue to red
  key? until rgb2-off ;

\ Try it: rgb2-init 100 40 0 rgb2-colour (orange)   or   rgb2-rainbow (Enter stops it)

\ rgb3: RGB LED, common cathode: red on PB10, green on PB11, blue on PB12
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB10 (pin 30) → 270 Ω → red LED → GND
$48000400 constant rgb3-red-port  \ GPIOB
10 constant rgb3-red-pin
\ PB11 (pin 33) → 56 Ω → green LED → GND
$48000400 constant rgb3-green-port  \ GPIOB
11 constant rgb3-green-pin
\ PB12 (pin 34) → 56 Ω → blue LED → GND
$48000400 constant rgb3-blue-port  \ GPIOB
12 constant rgb3-blue-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: rgb3-colour ( red green blue -- )               \ each: 0 = off, else on
  0<> if 1 rgb3-blue-pin lshift else 1 rgb3-blue-pin lshift 16 lshift then \ BSRR: blue on or off
  rgb3-blue-port $18 + !
  0<> if 1 rgb3-green-pin lshift else 1 rgb3-green-pin lshift 16 lshift then \ BSRR: green on or off
  rgb3-green-port $18 + !
  0<> if 1 rgb3-red-pin lshift else 1 rgb3-red-pin lshift 16 lshift then \ BSRR: red on or off
  rgb3-red-port $18 + ! ;
: rgb3-off ( -- ) 0 0 0 rgb3-colour ;
: rgb3-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  %11 rgb3-red-pin 2* lshift rgb3-red-port bic!   \ MODER: clear the two bits
  %01 rgb3-red-pin 2* lshift rgb3-red-port bis!   \ MODER: 01 = output
  1 rgb3-red-pin lshift rgb3-red-port 4 + bic!    \ OTYPER: push-pull
  %11 rgb3-green-pin 2* lshift rgb3-green-port bic! \ MODER: clear the two bits
  %01 rgb3-green-pin 2* lshift rgb3-green-port bis! \ MODER: 01 = output
  1 rgb3-green-pin lshift rgb3-green-port 4 + bic! \ OTYPER: push-pull
  %11 rgb3-blue-pin 2* lshift rgb3-blue-port bic! \ MODER: clear the two bits
  %01 rgb3-blue-pin 2* lshift rgb3-blue-port bis! \ MODER: 01 = output
  1 rgb3-blue-pin lshift rgb3-blue-port 4 + bic!  \ OTYPER: push-pull
  rgb3-off ;

\ Red, green, blue in turn until a key is pressed
: rgb3-cycle ( -- )
  rgb3-init
  begin
    1 0 0 rgb3-colour 300 ms  0 1 0 rgb3-colour 300 ms  0 0 1 rgb3-colour 300 ms
  key? until rgb3-off ;

\ Try it: rgb3-init 1 1 0 rgb3-colour (yellow)   or   rgb3-cycle (Enter stops it)

rgb-init 100 40 0 rgb-colour
rgb2-init 0 20 100 rgb2-colour
rgb3-init 1 0 1 rgb3-colour
10 ms
#!pins
rgb-off rgb2-off rgb3-off
10 ms
#!pins
