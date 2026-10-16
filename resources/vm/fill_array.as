# Fill a ten-word array with the values 1 to 10.
ldc R0 10       # how many words are left to fill
ldc R1 1        # the next value to store
ldc R2 @array   # the address of the next slot
ldc R3 1        # the constant one
loop:
str R1 R2       # memory[R2] = R1
add R1 R3       # next value
add R2 R3       # next slot
sub R0 R3       # one fewer to go
bne R0 @loop
hlt
.data array 10
