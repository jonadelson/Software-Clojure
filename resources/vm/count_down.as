# Print 5, 4, 3, 2, 1 and stop.
ldc R0 5        # the counter
ldc R1 1        # what we subtract each time round
loop:
prr R0
sub R0 R1
bne R0 @loop    # keep going while the counter is not zero
hlt
