package p

var a = []int{1, 2, 3}
var b = []int{
	1, 2,
	3,
}
var c = [][]string{{"a", "b"}, {"c"}}
var d = [...]struct{ x, y int }{
	{1, 2},
	{3, 4},
}
var e = []*T{&T{}, &T{x: 1}}

type T struct{ x int }
