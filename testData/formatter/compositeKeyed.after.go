package p

var m = map[string]int{
	"one":       1,
	"three":     3,
	"seventeen": 17,
}

type T struct{ A, Bbbbb, C int }

var t = T{
	A:     1,
	Bbbbb: 2,
	C:     3,
}

var mixed = map[string]T{
	"a": {A: 1},
	"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb": {C: 2},
	"c": {Bbbbb: 3},
}
