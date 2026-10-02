package p

func f(a, b, c, d int, s []int) int {
	x := a + b*c
	y := a*b + c*d
	z := (a + b) * (c - d)
	w := a<<2 | b&c
	v := -a + ^b
	u := a * -b
	t := a / *&b
	q := a - -b
	_ = s[a+1 : b-1]
	_ = s[a:b]
	_ = s[:len(s)-1]
	ok := a == b && c != d || a < b
	_ = f(a+b, c*d, a+b*c, d, s)
	_, _, _, _, _, _, _ = y, z, w, v, u, t, ok
	return q + a
}
