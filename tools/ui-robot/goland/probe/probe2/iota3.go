package probe2

const (
	a = iota
	b
	c = iota
)

const (
	a1, aa1 = iota, iota
	b1, bb1
	c1, cc1 = iota, iota
)

const (
	d = iota * 2
	e = iota * 2
)

const (
	f = 1 << iota
	g
	h
	i = 1 << iota
)

const (
	j Weekday = iota
	k
	l Weekday = iota
)

type Weekday int

func sw(w Weekday) {
	switch w {
	case j:
	}
}
