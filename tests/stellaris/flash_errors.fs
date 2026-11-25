\ A store into flash memory without FLASH_CR.PG sets PGSERR in FLASH_SR.
\ Until the error flags are cleared, the controller refuses every program
\ and erase.
42 $1F800 !
$40022010 @ hex.
\ Unlock, set PG, write a double word: refused.
$45670123 $40022008 !  $CDEF89AB $40022008 !  1 $40022014 !
$11111111 $1F800 !  $22222222 $1F804 !
$1F800 @ hex.
\ Writing the flags back clears them, and the same write works.
$40022010 @ $40022010 !  $40022010 @ hex.
$11111111 $1F800 !  $22222222 $1F804 !
$1F800 @ hex.  $1F804 @ hex.
0 $40022014 !  $80000000 $40022014 !
