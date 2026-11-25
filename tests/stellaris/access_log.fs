\ The access log (the Chip tab's Log): who wrote which register, from what
\ to what; a loop writing one register from one instruction is one entry
#!writes
$48000000 @ $C00 bic $400 or $48000000 !
: blink ( n -- ) 0 do $20 $48000018 ! $20 16 lshift $48000018 ! loop ;
100 blink
\ SysTick (the core's System Control Space), a peripheral, and the Registers view
5 $E000E010 !
#!dpoke 48000014 20
#!writes
