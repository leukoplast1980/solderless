\ Polling SysTick's COUNTFLAG with the interrupt off: the emulator must
\ treat the countdown as an event even without TICKINT.
$E000E010 constant STK_CTRL
$E000E014 constant STK_LOAD
$E000E018 constant STK_VAL
: wait-1s 16000000 1- STK_LOAD ! 0 STK_VAL ! %101 STK_CTRL ! begin STK_CTRL @ 65536 and until 0 STK_CTRL ! ;
: seconds 0 ?do i . wait-1s loop ." done" ;
3 seconds
