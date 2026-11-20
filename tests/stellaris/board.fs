\ The breadboard: an LED, a button, a pot, EXTI, ADC, PWM, DAC.
: wait ( n -- ) 0 do loop ;
$40021000 constant RCC
RCC $4C + constant AHB2ENR
RCC $58 + constant APB1ENR1
RCC $60 + constant APB2ENR
$48000800 constant GPIOC
$48000C00 constant GPIOD
#!attach 1 led PD2 r=270 vf=1900
#!attach 2 switch PC13
#!attach 3 pot PC0
#!pins
\ LED on PD2: output, high
%1000 AHB2ENR bis!
%11 4 lshift GPIOD bic!  %01 4 lshift GPIOD bis!
%100 GPIOD $18 + !
#!pins
\ Button on PC13 to GND, no pull-up yet: floating
%100 AHB2ENR bis!
%11 26 lshift GPIOC bic!
GPIOC $10 + @ drop
#!pins
\ Pull-up on, then press and release
%01 26 lshift GPIOC $0C + bis!
GPIOC $10 + @ 13 rshift 1 and .
#!input 2 1
GPIOC $10 + @ 13 rshift 1 and .
\ EXTI line 13 on port C, falling edge: the press sets PR1
1 APB2ENR bis!
$20 $40010014 bis!
1 13 lshift $4001040C bis!
1 13 lshift $40010400 bis!
#!input 2 0
$40010414 @ 13 rshift 1 and .
#!input 2 1
$40010414 @ 13 rshift 1 and .
1 13 lshift $40010414 !  $40010414 @ .
\ ADC1 channel 6 on PC0, clock HCLK/1
#!input 3 620
1 13 lshift AHB2ENR bis!
1 16 lshift $50000308 bis!
$50000008 constant ADC1_CR
1 29 lshift ADC1_CR bic!
1 28 lshift ADC1_CR bis!
100 wait
1 31 lshift ADC1_CR bis!
: until-clear ( mask addr -- ) begin 2dup @ and 0= until 2drop ;
1 31 lshift ADC1_CR until-clear
1 ADC1_CR bis!
: until-set ( mask addr -- ) begin 2dup @ and until 2drop ;
1 $50000000 until-set
6 6 lshift $50000030 !
%100 ADC1_CR bis!
%100 $50000000 until-set
$50000040 @ .
\ TIM3 CH1 on PC6: 1 kHz, 25 % PWM
%10 APB1ENR1 bis!
%11 12 lshift GPIOC bic!  %10 12 lshift GPIOC bis!
2 24 lshift GPIOC $20 + bis!
$40000400 constant TIM3
15 TIM3 $28 + !  999 TIM3 $2C + !  250 TIM3 $34 + !
$68 TIM3 $18 + !  1 TIM3 $20 + !  1 TIM3 $14 + !  1 TIM3 !
#!attach 4 led PC6 r=270 vf=1900
#!attach 6 audio PC6
#!audio
499 TIM3 $34 + !  10000 wait
#!audio
#!pins
\ DAC1 OUT1 on PA4 at half scale
#!attach 5 audio PA4
1 16 lshift AHB2ENR bis!
1 $50000800 bis!
2048 $50000808 !
#!pins
\ TIM6: wait for one update a second from now, by polling UIF
1 4 lshift APB1ENR1 bis!
$40001000 constant TIM6
15999 TIM6 $28 + !  999 TIM6 $2C + !  1 TIM6 $14 + !  0 TIM6 $10 + !  1 TIM6 !
1 TIM6 $10 + until-set  TIM6 $24 + @ 10 < .
