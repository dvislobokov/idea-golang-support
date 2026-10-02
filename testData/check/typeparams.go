package typeparams

type Foo struct{ f int }
type Far struct{ f float64 }

func convOk[X ~int, T ~int](x X) T { return T(x) }

func convFields[X Foo, T Far](x X) T {
	return T(x /* ERROR "cannot convert x (variable of type X constrained by Foo) to type T: cannot convert Foo (in X) to type Far (in T)" */)
}

func constConv[T ~byte | ~int]() T { return T(256 /* ERROR "cannot convert 256 (untyped int constant) to type T: constant 256 overflows byte (in T)" */) }

func constNotConstant[T ~byte]() {
	const _ = T /* ERROR "is not constant" */ (0)
}

func assignConst[T ~byte]() T { return 256 /* ERROR "cannot use 256 (untyped int constant) as T value in return statement" */ }

func ordered[T ~int | ~float32](x, y T) bool { return x < y }

func notOrdered[T any](x, y T) bool { return x /* ERROR "type parameter T cannot use operator <" */ < y }

func ranges[S ~[]int | ~[10]int](s S) {
	for range s /* ERROR "cannot range over s (variable of type S constrained by ~[]int | ~[10]int): []int and [10]int have different underlying types" */ {
	}
}

func makes[M ~map[string]int | ~chan int, C ~chan int | ~chan string]() {
	_ = make(M /* ERROR "cannot make M: map[string]int and chan int have different underlying types" */)
	_ = make(C /* ERROR "cannot make C: channels chan int and chan string have different element types" */)
}

func assertions[T any](x T) {
	_ = x /* ERROR "cannot use type assertion on type parameter value x" */ .(int)
	switch x /* ERROR "cannot use type switch on type parameter value x" */ .(type) {
	}
}

func slices[T interface{ ~[]byte | ~[]int }](x T) {
	_ = x /* ERROR "cannot slice x (variable of type T constrained by interface{~[]byte | ~[]int}): []byte and []int have different underlying types" */ [0:1]
}

func calls[P int | string](x P) {
	x /* ERROR "cannot call x (variable of type P constrained by int | string): int is not a function" */ ()
}
