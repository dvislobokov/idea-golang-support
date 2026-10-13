// A string constant doubled 31 times (GOROOT fixedbugs/issue78346.go): go/types stops at 2e9 bytes; the checker used to build the text
// and ran the IDE out of memory.
package p

const (
	a = "x"
	b = a + a
	c = b + b
	d = c + c
	e = d + d
	f = e + e
	g = f + f
	h = g + g
	i = h + h
	j = i + i
	k = j + j
	l = k + k
	m = l + l
	n = m + m
	o = n + n
	p = o + o
	q = p + p
	r = q + q
	s = r + r
	t = s + s
	u = t + t
	v = u + u
	w = v + v
	x = w + w
	y = x + x
	z = y + y
	A = z + z
	B = A + A
	C = B + B
	D = C + C
	E = D + D
	F = E + /* ERROR "constant string too long" */ E
	G = F + F
)

const short = "ab" + "cd"
// the length of a held-by-length constant is still a constant
var _ [len(z) - 33554432]int
var _ [len(short) - 4]int
