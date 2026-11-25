\ The fault explainer's record: a load from where nothing is mapped (@ on
\ a wrong address) raises a BusFault, which Stellaris takes as HardFault.
\ With the fault break on (as in the app) the chip stops right there.
#!fault
#!timeout 500
#!faultbreak 1
: show-temp ( -- ) $60000000 @ . ;
: main ( -- ) 3 show-temp ;
main
#!fault
\ Resumed, Stellaris' handler prints once, returns, and the load faults again
#!resume
#!fault
#!faultbreak 0
#!powercycle
#!resume
#!fault
