package inferunknown

import "example.com/missing"

func New[T any](n int) *T { return nil }

func Conv[T any](x any) T { var t T; return t }

func Keep[T any](x T) T { return x }

func Elems[S ~[]E, E any](s S) E { var e E; return e }

func Pick[T any, U any](u U) T { var t T; return t }

func unknownArgs() {
	v := missing.Get()
	_ = New(v) // ERROR "in call to New, cannot infer T"
	_ = Conv(v) // ERROR "in call to Conv, cannot infer T"
	_ = Keep(v)
	_ = Elems(v)
	_ = Pick(v) // ERROR "in call to Pick, cannot infer T"
	_ = New(missing.Count())
	_ = New(undefinedName) // ERROR "undefined: undefinedName"
	_ = New[int](v)
}
