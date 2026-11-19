\ Compile into flash, use, then erase. eraseflash resets the chip.
compiletoflash
: cube dup dup * * ;
compiletoram
5 cube .
eraseflash
cube
