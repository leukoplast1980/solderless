\ The scope: a bouncing button on PC13, traced
#!attach 2 switch PC13 bounce=2000
#!trace PC13
#!scope
$48000800 constant GPIOC
%100 $4002104C bis!
%11 26 lshift GPIOC bic!
%01 26 lshift GPIOC $0C + bis!
#!scope
#!input 2 1
: wait 0 do loop ; 20000 wait
#!scope
