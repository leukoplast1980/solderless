\ A KY-040 rotary encoder on PA0 (CLK, TIM2 CH1) and PA1 (DT, TIM2 CH2),
\ its push switch on PC3: TIM2 in encoder mode 3 counts four per detent,
\ up clockwise, down the other way, wrapping at ARR; the contacts bounce
\ but the bounces cancel out. Then mode 1 (two per detent), and the switch
\ that needs a pull-up the module does not have.
#!attach 1 encoder PA0 PA1 PC3
$48000000 constant GPIOA  $48000800 constant GPIOC  $40000000 constant TIM2
: enc-init ( -- )
  %101 $4002104C bis!  1 $40021058 bis!                  \ GPIOA, GPIOC; TIM2
  %1111 GPIOA bic!  %1010 GPIOA bis!                     \ PA0 PA1 alternate function
  $FF GPIOA $20 + bic!  $11 GPIOA $20 + bis!             \ AF1: TIM2 CH1, CH2
  %11 6 lshift GPIOC bic! ;                              \ PC3 input
: enc-start ( sms -- ) 0 TIM2 !  TIM2 8 + !  $0101 TIM2 $18 + !  0 TIM2 $20 + !
  99 TIM2 $2C + !  0 TIM2 $24 + !  1 TIM2 ! ;            \ CC1S CC2S = 01, ARR 99, CNT 0, CEN
: cnt ( -- n ) TIM2 $24 + @ ;
: ms ( n -- ) 15999 $E000E014 !  0 $E000E018 !  5 $E000E010 !
  0 ?do begin $E000E010 @ $10000 and until loop  0 $E000E010 ! ;
: wait ( -- ) 100 ms ;
enc-init 3 enc-start cnt .
#!turn 1 1
wait cnt .  TIM2 @ 4 rshift 1 and .
#!turn 1 3
wait cnt .
#!turn 1 -5
wait cnt .  TIM2 @ 4 rshift 1 and .  TIM2 $10 + @ 1 and .
1 enc-start
#!turn 1 2
wait cnt .
0 TIM2 $18 + !  0 TIM2 !  3 TIM2 8 + !  0 TIM2 $24 + !  1 TIM2 !
#!turn 1 1
wait cnt .
GPIOC $10 + @ 3 rshift 1 and .
1 6 lshift GPIOC $0C + bis!  GPIOC $10 + @ 3 rshift 1 and .
#!input 1 1
wait GPIOC $10 + @ 3 rshift 1 and .
