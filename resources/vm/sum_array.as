# Add up the words of an array and print the total.
# The array is filled with 1 to 5 by the .word lines, so the answer is 15.
ldc R0 0        # the running total
ldc R1 @array   # the address of the next word
ldc R2 5        # how many words are left
ldc R3 1        # the constant one
loop:
beq R2 @done    # stop when there is nothing left to add
ldr R3 R1       # borrow R3 to hold memory[R1]
add R0 R3       # total = total + that word
ldc R3 1        # and make it one again
add R1 R3       # next address
sub R2 R3       # one fewer to go
bne R3 @loop    # R3 is always one, so this always branches
done:
prr R0
hlt
array:
.word 1
.word 2
.word 3
.word 4
.word 5
