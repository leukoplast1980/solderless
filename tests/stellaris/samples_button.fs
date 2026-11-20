\ The sample code the Board tab gives for buttons, as generated (SamplesTest
\ checks that it still matches): pull-up, external pull-down, EXTI flags.
#!attach 1 switch PC13
#!attach 2 switch PB4 vcc
#!attach 3 pull PB4
#!attach 4 switch PA0 bounce=2000

\ button: push button from PC13 to GND
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PC13 (pin 2) → button → GND, pull-up inside the chip (set it in GPIOC_PUPDR)
$48000800 constant button-port  \ GPIOC
13 constant button-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: button-init ( -- )
  1 2 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOC
  %11 button-pin 2* lshift button-port bic!       \ MODER: 00 = input
  %11 button-pin 2* lshift button-port $0C + bic! \ PUPDR: clear the two bits
  %01 button-pin 2* lshift button-port $0C + bis! ; \ PUPDR: 01 = pull-up, high until pressed
: button? ( -- flag )                             \ true while pressed
  button-port $10 + @  1 button-pin lshift and 0= ; \ IDR: low = pressed

\ Report each press and release until a key is pressed
: button-watch ( -- )
  button-init button?
  begin
    button? tuck <> if dup if ." pressed" else ." released" then cr then
    10 ms  \ reading every 10 ms rides over the bounce
  key? until drop ;

\ Try it: button-init button? .   or   button-watch (Enter stops it)

\ button2: push button from PB4 to 3V3
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PB4 (pin 57) → button → 3V3, 10 kΩ to GND
$48000400 constant button2-port  \ GPIOB
4 constant button2-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: button2-init ( -- )
  1 1 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOB
  %11 button2-pin 2* lshift button2-port bic!     \ MODER: 00 = input
  %11 button2-pin 2* lshift button2-port $0C + bic! \ PUPDR: 00, no pull: the 10 kΩ resistor holds the pin low
  1 0 lshift $40021060 bis!                       \ RCC APB2ENR: clock for SYSCFG
  $F 0 lshift $4001000C bic!                      \ SYSCFG EXTICR2: clear EXTI4's port
  1 0 lshift $4001000C bis!                       \ SYSCFG EXTICR2: 1, EXTI4 watches GPIOB
  1 4 lshift $40010408 bis!                       \ EXTI RTSR1: a rising edge (the press) sets the flag
  1 4 lshift $40010400 bis!                       \ EXTI IMR1: line 4 on (its interrupt stays off in the NVIC)
  1 4 lshift $40010414 ! ;                        \ EXTI PR1: clear the flag
: button2? ( -- flag )                            \ true while pressed
  button2-port $10 + @  1 button2-pin lshift and 0<> ; \ IDR: high = pressed
: button2-pressed? ( -- flag )                    \ true once for each press
  $40010414 @ 1 4 lshift and 0<>                  \ EXTI PR1: a press since last time?
  dup if 5 ms then                                \ let the contacts settle: they bounce for about 2 ms
  1 4 lshift $40010414 !                          \ PR1: clear it and the bounces, by writing 1
  button2? and ;                                  \ and still pressed: not a bounce on release

\ Count presses until a key is pressed. The EXTI flag marks each
\ press, so it counts once however long the button is held.
: button2-count ( -- )
  button2-init 0
  begin
    button2-pressed? if 1+ dup . cr then
    10 ms
  key? until drop ;

\ Try it: button2-init button2? .   or   button2-count (Enter stops it)

\ button3: push button from PA0 to GND
\ Paste all of it into the console, or put it in a file after wiring.fs.

\ PA0 (pin 12) → button → GND, pull-up inside the chip (set it in GPIOA_PUPDR)
$48000000 constant button3-port  \ GPIOA
0 constant button3-pin

\ ms: wait n milliseconds, counted by SysTick at 16 MHz
\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)
: ms ( n -- )
  15999 $E000E014 !  0 $E000E018 !  5 $E000E010 ! \ SysTick: wrap every 16000 cycles
  0 ?do begin $E000E010 @ $10000 and until loop   \ count the wraps
  0 $E000E010 ! ;                                 \ SysTick off again

: button3-init ( -- )
  1 0 lshift $4002104C bis!                       \ RCC AHB2ENR: clock for GPIOA
  %11 button3-pin 2* lshift button3-port bic!     \ MODER: 00 = input
  %11 button3-pin 2* lshift button3-port $0C + bic! \ PUPDR: clear the two bits
  %01 button3-pin 2* lshift button3-port $0C + bis! \ PUPDR: 01 = pull-up, high until pressed
  1 0 lshift $40021060 bis!                       \ RCC APB2ENR: clock for SYSCFG
  $F 0 lshift $40010008 bic!                      \ SYSCFG EXTICR1: 0, EXTI0 watches GPIOA
  1 0 lshift $4001040C bis!                       \ EXTI FTSR1: a falling edge (the press) sets the flag
  1 0 lshift $40010400 bis!                       \ EXTI IMR1: line 0 on (its interrupt stays off in the NVIC)
  1 0 lshift $40010414 ! ;                        \ EXTI PR1: clear the flag
: button3? ( -- flag )                            \ true while pressed
  button3-port $10 + @  1 button3-pin lshift and 0= ; \ IDR: low = pressed
: button3-pressed? ( -- flag )                    \ true once for each press
  $40010414 @ 1 0 lshift and 0<>                  \ EXTI PR1: a press since last time?
  dup if 5 ms then                                \ let the contacts settle: they bounce for about 2 ms
  1 0 lshift $40010414 !                          \ PR1: clear it and the bounces, by writing 1
  button3? and ;                                  \ and still pressed: not a bounce on release

\ Count presses until a key is pressed. The EXTI flag marks each
\ press, so it counts once however long the button is held.
: button3-count ( -- )
  button3-init 0
  begin
    button3-pressed? if 1+ dup . cr then
    10 ms
  key? until drop ;

\ Try it: button3-init button3? .   or   button3-count (Enter stops it)

button-init button? .
#!input 1 1
button? .
#!input 1 0
button2-init button2? . button2-pressed? .
#!input 2 1
button2-pressed? . button2-pressed? . button2? .
#!input 2 0
button2-pressed? .
button3-init
#!input 4 1
button3-pressed? . button3-pressed? .
#!input 4 0
button3-pressed? .
#!input 4 1
#!input 4 0
button3-pressed? .
