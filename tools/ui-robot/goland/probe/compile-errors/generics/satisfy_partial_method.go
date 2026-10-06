package probe

type smStringer interface{ String() string }

func smShow[A smStringer, B any](a A, b B) {}

func smUse() {
	// want: in call to smShow[int], A (type int) does not satisfy smStringer (missing method String)
	smShow[int](1, "x")
}
