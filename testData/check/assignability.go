package assignability

type I interface{ M() }

type T struct{}

func (T) M() {}

type P struct{}

func (*P) M() {}

type N struct{}

type MyInt int

func pair() (int, string) { return 0, "" }

func f() {
	var i int
	var s string
	i = s // ERROR "cannot use s (variable of type string) as int value in assignment"
	s = 1 // ERROR "cannot use 1 (untyped int constant) as string value in assignment"
	var i8 int8 = 256 // ERROR "cannot use 256 (untyped int constant) as int8 value in variable declaration (overflows)"
	var u uint = -1 // ERROR "cannot use -1 (untyped int constant) as uint value in variable declaration (overflows)"
	var i2 int = 1.5 // ERROR "cannot use 1.5 (untyped float constant) as int value in variable declaration (truncated)"
	var f32 float32 = 1.5
	var m MyInt = 3
	i = m // ERROR "cannot use m (variable of int type MyInt) as int value in assignment"
	m = MyInt(i)
	var x I = T{}
	x = &T{}
	x = &P{}
	x = P{} // ERROR "cannot use P{} (value of struct type P) as I value in assignment: P does not implement I (method M has pointer receiver)"
	x = N{} // ERROR "cannot use N{} (value of struct type N) as I value in assignment: N does not implement I (missing method M)"
	var a []int = nil
	i = nil // ERROR "cannot use nil as int value in assignment"
	var any1 any = 1
	var e error = nil
	_, _, _, _, _, _, _, _, _, _ = i, s, i8, u, i2, f32, x, a, any1, e
	var n, str = pair()
	var bad1, bad2 int = pair() // ERROR "cannot use pair() (value of type string) as int value in variable declaration"
	_, _, _, _ = n, str, bad1, bad2
	n = len("x")
	s = "a" + "b"
	s = 'a' // ERROR "cannot use 'a' (untyped rune constant 97) as string value in assignment"
	var r rune = 'a'
	var b byte = 'a'
	_, _ = r, b
	var c2 complex128 = 1
	var f2 float64 = 1
	_, _ = c2, f2
	i = i8 // ERROR "cannot use i8 (variable of type int8) as int value in assignment"
	var arr [2]int
	var sl []int = arr[:]
	_ = sl
	var ch chan int
	var rch <-chan int = ch
	_ = rch
	ch = rch // ERRORx `cannot use rch \(variable of type <-chan int\) as chan int value in assignment`
	var fn func(int) int
	fn = func(x int) int { return x }
	fn = func(x string) int { return 0 } // ERROR "cannot use func(x string) int {...} (value of type func(x string) int) as func(int) int value in assignment"
	_ = fn
}

func g() (int, error) {
	return 1 // ERROR "not enough return values"
}

func h() int {
	return 1, nil // ERROR "too many return values"
}

func k() string {
	return 1 // ERROR "cannot use 1 (untyped int constant) as string value in return statement"
}

func ok() (int, string) {
	return pair()
}
