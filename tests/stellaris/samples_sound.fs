\ The sample code the Board tab gives for potentiometers, speakers and piezo
\ buzzers, as generated (SamplesTest checks that it still matches).
#!attach 1 pot PC0
#!attach 2 pot PB2
#!attach 3 audio PA4
#!attach 4 audio PB6
#!attach 5 audio PB8
#!input 1 250
#!input 2 800

\ pot: potentiometer, wiper on PC0 (ADC1 IN6)
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ potentiometer 10 kΩ between 3V3 and GND, wiper → PC0 (pin 8)
$48000800 constant pot-port  \ GPIOC
0 constant pot-pin
$50000000 constant pot-adc  \ ADC1
6 constant pot-ch

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: pot-init ( -- )
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  %11 pot-pin 2* lshift pot-port bis!             \ MODER: 11 = analog
  1 13 lshift $4002104C bis!                      \ RCC AHB2ENR: clock for ADC1 and ADC2
  1 16 lshift $50000308 bis!                      \ ADC12 CCR: CKMODE 01, the ADC runs on the 16 MHz HCLK
  pot-adc 8 + @ 1 and 0= if                       \ CR: ADEN clear? (another part may have set it up)
    1 29 lshift pot-adc 8 + bic!                  \ CR: DEEPPWD off, out of deep power-down
    1 28 lshift pot-adc 8 + bis!                  \ CR: ADVREGEN, voltage regulator on
    1 ms                                          \ the regulator needs 20 µs
    1 31 lshift pot-adc 8 + bis!                  \ CR: ADCAL, calibrate
    begin pot-adc 8 + @ 1 31 lshift and 0= until  \ until ADCAL clears
    1 pot-adc 8 + bis!                            \ CR: ADEN, ADC on
    begin pot-adc @ 1 and until                   \ ISR: until ADRDY
  then
  %111 18 lshift pot-adc $14 + bic!               \ SMPR1: clear IN6's sample time
  %100 18 lshift pot-adc $14 + bis! ;             \ SMPR1: 47.5 cycles, time to charge through the pot
: pot@ ( -- u )                                   \ 0 at GND .. 4095 at 3.3 V
  pot-ch 6 lshift pot-adc $30 + !                 \ SQR1: one conversion, of IN6
  %100 pot-adc 8 + bis!                           \ CR: ADSTART
  begin pot-adc @ %100 and until                  \ ISR: until EOC
  pot-adc $40 + @ ;                               \ DR: the result; reading it clears EOC
: pot-mv ( -- mV ) pot@ 3300 4095 */ ;

\ Print the voltage when it changes, until a key is pressed
: pot-watch ( -- )
  pot-init -100
  begin
    pot-mv 2dup - abs 10 > if nip dup . ." mV" cr else drop then
    100 ms
  key? until drop ;

\ Try it: pot-init pot@ .   or   pot-watch (Enter stops it)

\ pot2: potentiometer, wiper on PB2 (ADC2 IN12)
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ potentiometer 10 kΩ between 3V3 and GND, wiper → PB2 (pin 26)
$48000400 constant pot2-port  \ GPIOB
2 constant pot2-pin
$50000100 constant pot2-adc  \ ADC2
12 constant pot2-ch

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: pot2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  %11 pot2-pin 2* lshift pot2-port bis!           \ MODER: 11 = analog
  1 13 lshift $4002104C bis!                      \ RCC AHB2ENR: clock for ADC1 and ADC2
  1 16 lshift $50000308 bis!                      \ ADC12 CCR: CKMODE 01, the ADC runs on the 16 MHz HCLK
  pot2-adc 8 + @ 1 and 0= if                      \ CR: ADEN clear? (another part may have set it up)
    1 29 lshift pot2-adc 8 + bic!                 \ CR: DEEPPWD off, out of deep power-down
    1 28 lshift pot2-adc 8 + bis!                 \ CR: ADVREGEN, voltage regulator on
    1 ms                                          \ the regulator needs 20 µs
    1 31 lshift pot2-adc 8 + bis!                 \ CR: ADCAL, calibrate
    begin pot2-adc 8 + @ 1 31 lshift and 0= until \ until ADCAL clears
    1 pot2-adc 8 + bis!                           \ CR: ADEN, ADC on
    begin pot2-adc @ 1 and until                  \ ISR: until ADRDY
  then
  %111 6 lshift pot2-adc $18 + bic!               \ SMPR2: clear IN12's sample time
  %100 6 lshift pot2-adc $18 + bis! ;             \ SMPR2: 47.5 cycles, time to charge through the pot
: pot2@ ( -- u )                                  \ 0 at GND .. 4095 at 3.3 V
  pot2-ch 6 lshift pot2-adc $30 + !               \ SQR1: one conversion, of IN12
  %100 pot2-adc 8 + bis!                          \ CR: ADSTART
  begin pot2-adc @ %100 and until                 \ ISR: until EOC
  pot2-adc $40 + @ ;                              \ DR: the result; reading it clears EOC
: pot2-mv ( -- mV ) pot2@ 3300 4095 */ ;

\ Print the voltage when it changes, until a key is pressed
: pot2-watch ( -- )
  pot2-init -100
  begin
    pot2-mv 2dup - abs 10 > if nip dup . ." mV" cr else drop then
    100 ms
  key? until drop ;

\ Try it: pot2-init pot2@ .   or   pot2-watch (Enter stops it)

\ speaker: speaker, driven by PA4 (DAC1 OUT1)
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PA4 (pin 18) → 1 kΩ → NPN base; speaker from 3V3 to the collector, 1N4148 across the speaker; emitter → GND
$48000000 constant speaker-port  \ GPIOA
4 constant speaker-pin
1 constant speaker-dac-ch  \ DAC1 OUT1

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: speaker-out ( u -- ) $50000808 ! ;           \ DAC1 DHR12R1: 0 .. 4095 is 0 .. 3.3 V
: speaker-init ( -- )
  1 0 lshift $4002104C bis!                    \ RCC AHB2ENR: clock for GPIOA
  %11 speaker-pin 2* lshift speaker-port bis!  \ MODER: 11 = analog
  1 16 lshift $4002104C bis!                   \ RCC AHB2ENR: clock for DAC1
  1 0 lshift $50000800 bis!                    \ DAC1 CR: EN1, OUT1 on
  0 speaker-out ;                              \ low: no current through the speaker
: speaker-tone ( ms hz -- )
  dup 8000000 swap / 1- $E000E014 !            \ SysTick: wrap twice a period
  0 $E000E018 !  5 $E000E010 !
  * 500 /  0 ?do                               \ ms × hz / 500 half periods
    i 1 and if 0 else 4095 then speaker-out    \ a square wave: high, low, ...
    begin $E000E010 @ $10000 and until         \ until SysTick wraps
  loop  0 speaker-out  0 $E000E010 ! ;         \ end low; SysTick off again

\ Play a scale, C to C
: speaker-scale ( -- )
  speaker-init  523 494 440 392 349 330 294 262
  8 0 do 250 swap speaker-tone loop ;

\ Try it: speaker-init 500 440 speaker-tone   or   speaker-scale

\ speaker2: speaker, driven by PB6 (TIM4 CH1)
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB6 (pin 59) → 1 kΩ → NPN base; speaker from 3V3 to the collector, 1N4148 across the speaker; emitter → GND
$48000400 constant speaker2-port  \ GPIOB
6 constant speaker2-pin
$40000800 constant speaker2-timer  \ TIM4
1 constant speaker2-ch
2 constant speaker2-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: speaker2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  %11 speaker2-pin 2* lshift speaker2-port bic!   \ MODER: clear the two bits
  %10 speaker2-pin 2* lshift speaker2-port bis!   \ MODER: 10 = alternate function
  1 speaker2-pin lshift speaker2-port 4 + bic!    \ OTYPER: push-pull
  $F speaker2-pin 4 * lshift speaker2-port $20 + bic! \ AFRL: clear the four bits
  speaker2-af speaker2-pin 4 * lshift speaker2-port $20 + bis! \ AFRL: AF2 = TIM4 CH1
  1 2 lshift $40021058 bis!                       \ RCC APB1ENR1: clock for TIM4
  15 speaker2-timer $28 + !                       \ PSC: 16 MHz / 16, a tick every microsecond
  $FF speaker2-timer $18 + bic!                   \ CCMR1: clear channel 1
  $68 speaker2-timer $18 + bis!                   \ CCMR1: channel 1 in PWM mode 1, CCR1 preloaded
  $F speaker2-timer $20 + bic!                    \ CCER: clear channel 1
  $1 speaker2-timer $20 + bis!                    \ CCER: CC1E, output on
  0 speaker2-timer $34 + !                        \ CCR1: 0, quiet
  1 speaker2-timer $14 + !                        \ EGR: UG, load PSC and ARR now
  $81 speaker2-timer bis! ;                       \ CR1: ARPE and CEN, the timer runs
: speaker2-tone ( hz -- )                         \ 20 .. 20000 Hz, until speaker2-quiet
  1000000 swap / 1-  dup speaker2-timer $2C + !   \ ARR: microseconds a period, less one
  1+ 2/ speaker2-timer $34 + !                    \ CCR1: high for half of it
  1 speaker2-timer $14 + ! ;                      \ EGR: UG, the new period starts now
: speaker2-quiet ( -- ) 0 speaker2-timer $34 + ! ; \ CCR1: 0, the pin stays low

\ Play a scale, C to C
: speaker2-scale ( -- )
  speaker2-init  523 494 440 392 349 330 294 262
  8 0 do speaker2-tone 250 ms loop  speaker2-quiet ;

\ Try it: speaker2-init 440 speaker2-tone 500 ms speaker2-quiet   or   speaker2-scale

\ piezo: piezo buzzer on PB8 (TIM16 CH1)
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB8 (pin 61) → piezo → GND
$48000400 constant piezo-port  \ GPIOB
8 constant piezo-pin
$40014400 constant piezo-timer  \ TIM16
1 constant piezo-ch
1 constant piezo-af

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: piezo-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  %11 piezo-pin 2* lshift piezo-port bic!         \ MODER: clear the two bits
  %10 piezo-pin 2* lshift piezo-port bis!         \ MODER: 10 = alternate function
  1 piezo-pin lshift piezo-port 4 + bic!          \ OTYPER: push-pull
  $F piezo-pin 8 - 4 * lshift piezo-port $24 + bic! \ AFRH: clear the four bits
  piezo-af piezo-pin 8 - 4 * lshift piezo-port $24 + bis! \ AFRH: AF1 = TIM16 CH1
  1 17 lshift $40021060 bis!                      \ RCC APB2ENR: clock for TIM16
  15 piezo-timer $28 + !                          \ PSC: 16 MHz / 16, a tick every microsecond
  $FF piezo-timer $18 + bic!                      \ CCMR1: clear channel 1
  $68 piezo-timer $18 + bis!                      \ CCMR1: channel 1 in PWM mode 1, CCR1 preloaded
  $F piezo-timer $20 + bic!                       \ CCER: clear channel 1
  $1 piezo-timer $20 + bis!                       \ CCER: CC1E, output on
  0 piezo-timer $34 + !                           \ CCR1: 0, quiet
  $8000 piezo-timer $44 + bis!                    \ BDTR: MOE, outputs on (TIM16 needs it)
  1 piezo-timer $14 + !                           \ EGR: UG, load PSC and ARR now
  $81 piezo-timer bis! ;                          \ CR1: ARPE and CEN, the timer runs
: piezo-tone ( hz -- )                            \ 20 .. 20000 Hz, until piezo-quiet
  1000000 swap / 1-  dup piezo-timer $2C + !      \ ARR: microseconds a period, less one
  1+ 2/ piezo-timer $34 + !                       \ CCR1: high for half of it
  1 piezo-timer $14 + ! ;                         \ EGR: UG, the new period starts now
: piezo-quiet ( -- ) 0 piezo-timer $34 + ! ;      \ CCR1: 0, the pin stays low

\ Play a scale, C to C
: piezo-scale ( -- )
  piezo-init  523 494 440 392 349 330 294 262
  8 0 do piezo-tone 250 ms loop  piezo-quiet ;

\ Try it: piezo-init 440 piezo-tone 500 ms piezo-quiet   or   piezo-scale

pot-init pot2-init pot-mv . pot2-mv .
#!input 1 900
pot-mv .
speaker-init 4 1000 speaker-tone
#!audio
speaker2-init 440 speaker2-tone  piezo-init 2000 piezo-tone  10 ms
#!audio
speaker2-quiet piezo-quiet  10 ms
#!audio
