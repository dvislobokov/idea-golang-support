package controlflow

import "unsafe"

func results() int {
	for {
	}
}

func missing(b bool) int {
	if b {
		return 1
	}
} // ERROR "missing return"

func panics() int { panic("x") }

func switches(x int) int {
	switch x {
	case 1:
		return 1
	default:
		return 0
	}
}

func goDefer() {
	var c chan int
	go int /* ERROR "go requires function call, not conversion" */ (0)
	defer len /* ERROR "defer discards result of len(c)" */ (c)
	defer close(c)
}

func blankLabels() {
_:
	for {
		break _ /* ERROR "invalid break label _" */
	}
	goto _ /* ERROR "label _ not declared" */
}

func typeSwitch(x interface{}) {
	switch x.(type) {
	case nil:
	case func(int):
	case nil /* ERROR "duplicate case nil in type switch" */ :
	case func /* ERROR "duplicate case func(y int) in type switch" */ (y int):
	}
	switch _ /* ERROR "no new variable on left side of :=" */ := x.(type) {
	}
}

func ranges() {
	for _ := /* ERROR "no new variables on left side of :=" */ range "" {
	}
}

func iotas() {
	_ = iota /* ERROR "cannot use iota outside constant declaration" */
}

const inClosure = unsafe.Sizeof(func() { _ = iota })

type T struct{}

func (*T) p() {}

func pointerMethods() {
	_ = T{}.p /* ERROR "cannot call pointer method p on T" */
	var t T
	_ = t.p
}

func unsafeUnused(x int) {
	unsafe /* ERROR "is not used" */ .Sizeof(x)
	unsafe.Slice(nil /* ERROR "nil is not a pointer" */, 0)
}

func makeSizes() {
	_ = make([]int, - /* ERROR "must not be negative" */ 1)
	_ = make([]int, 10 /* ERROR "length and capacity swapped" */, 9)
}
