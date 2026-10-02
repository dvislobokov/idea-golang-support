package literals

type T struct {
	A int
	B string
}

type Inner struct{ X int }

type Outer struct {
	In  Inner
	Ptr *Inner
	L   []Inner
	M   map[string]Inner
}

func f() {
	_ = T{1, "a"}
	_ = T{1} // ERROR "too few values in struct literal of type T"
	_ = T{1, "a", 2} // ERROR "too many values in struct literal of type T"
	_ = T{A: 1, "a"} // ERROR "mixture of field:value and value elements in struct literal"
	_ = T{A: 1, A: 2} // ERROR "duplicate field name A in struct literal"
	_ = T{A: "x"} // ERROR `cannot use "x" (untyped string constant) as int value in struct literal`
	_ = T{"x", "a"} // ERROR `cannot use "x" (untyped string constant) as int value in struct literal`
	_ = []int{1, 2, "a"} // ERROR `cannot use "a" (untyped string constant) as int value in array or slice literal`
	_ = [2]int{1, 2, 3} // ERROR "index 2 out of bounds [0:2]"
	_ = [...]int{1, 2, 3}
	_ = []int{0: 1, 0: 2} // ERROR "duplicate index 0 in array or slice literal"
	_ = map[string]int{"a": 1, "a": 2} // ERROR `duplicate key "a" in map literal`
	_ = map[string]int{1: 1} // ERROR "cannot use 1 (untyped int constant) as string value in map literal"
	_ = map[string]int{"a": "b"} // ERROR `cannot use "b" (untyped string constant) as int value in map literal`
	_ = map[string]int{1} // ERROR "missing key in map literal"
	_ = Outer{In: Inner{X: 1}, Ptr: &Inner{1}, L: []Inner{{X: 1}, {2}}, M: map[string]Inner{"a": {X: 3}}}
	_ = []*Inner{{X: 1}}
	_ = Outer{L: []Inner{{Y: 1}}} // ERROR "unknown field Y in struct literal of type Inner"
	_ = []int{1, 2}[1]
	var arr [3]int
	_ = arr[3] // ERROR "invalid argument: index 3 out of bounds [0:3]"
	_ = arr[-1] // ERROR "must not be negative"
	_ = arr["a"] // ERROR `cannot convert "a" (untyped string constant) to type int`
	var n int
	_ = n[0] // ERROR "invalid operation: cannot index n (variable of type int)"
	_ = "abc"[1]
	_ = n[1:] // ERROR "cannot slice n (variable of type int)"
	var s []int
	_ = s[1:2]
	var m map[string]int
	_ = m[1] // ERROR "cannot use 1 (untyped int constant) as string value in map index"
}
