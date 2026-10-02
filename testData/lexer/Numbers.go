package numbers

// decimal, binary, octal, hex integers
0 1 42 1_000_000 0b1011 0B1 0b_1 0o17 0O17 0o_7 017 0_17 0x1F 0XaBcD 0x_1

// floats
0. 1.5 .5 1e3 1E+3 1e-3 1.5e3 .5e-2 0e0 1_0.2_5 08.5 09e1

// hex floats
0x1p4 0x1P-2 0x1.8p1 0x.8p0 0X_1p+2 0x1.p0

// imaginary
1i 0i 1.5i .5i 1e3i 0x1i 0b1i 0o7i 017i 0x1p4i

// malformed but lexable (errors belong to later phases)
0x 0b 0o 1__2 1_ 0b102 0o8 09 0x1.5 1e 1e+ 1p5 0b1.0 0x1e+2

// numbers next to other tokens
a.5 x.y 1..2 f(1)i 3.String
