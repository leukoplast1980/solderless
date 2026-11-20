\ Basic interpreter, arithmetic and memory
1 2 + .
-7 3 /mod . .
hex FF decimal .
: sq dup * ;
12 sq .
1000000 1000000 um* d.
create buf 16 allot
$12345678 buf ! buf c@ hex. buf 2 + h@ hex.
nosuchword
