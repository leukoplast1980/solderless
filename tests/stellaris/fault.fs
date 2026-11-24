\ The fault explainer's record: a load from where nothing is mapped (@ on
\ a wrong address) raises a BusFault, which Stellaris takes as HardFault.
\ Its handler would print and retry forever; this one parks the core.
#!fault
#!timeout 500
: park ( -- ) begin again ;
' park irq-fault !
: show-temp ( -- ) $60000000 @ . ;
: main ( -- ) 3 show-temp ;
main
#!fault
#!powercycle
#!fault
