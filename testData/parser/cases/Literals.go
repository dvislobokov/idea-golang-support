// Exercises every literal form: integers (decimal, binary, octal, hex, underscores),
// floats (decimal and hex), imaginary, runes with escapes, interpreted and raw strings,
// composite literals, function literals. Type-checks.
package cases

var (
	i1 = 42
	i2 = 0b1010_1010
	i3 = 0o755
	i4 = 0755
	i5 = 0xDead_Beef
	i6 = 1_000_000

	f1 = 3.14
	f2 = .5
	f3 = 1e10
	f4 = 1e-3
	f5 = 6.02e+23
	f6 = 0x1p-2
	f7 = 0x1.8p3
	f8 = 1_0.2_5

	c1 = 1i
	c2 = 2.5i
	c3 = 0b11i
	c4 = 123i

	r1 = 'a'
	r2 = '\n'
	r3 = '\x41'
	r4 = 'é'
	r5 = '\U0001F600'
	r6 = '\''
	r7 = '\101'
	r8 = '世'

	s1 = "hello\t\"world\"\n"
	s2 = `raw \n string
spanning lines`
	s3 = "é\xff\377"
	s4 = ""

	l1 = []int{1, 2, 3}
	l2 = [...]string{2: "c", 0: "a"}
	l3 = map[string][]int{"a": {1}, "b": nil}
	l4 = struct {
		X, Y int
	}{1, 2}
	l5 = &struct{ s string }{"x"}
	l6 = [][]int{{1}, {2, 3}}
	l7 = func(a int) int { return a + 1 }
	l8 = []*struct{ n int }{{1}, {2}}
)
