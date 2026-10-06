package probe

type pvStringer interface{ String() string }

func pvShow[A pvStringer, B any](a A, b B) {}

func pvUse() {
	// want: A (type int) does not satisfy pvStringer (missing method String)
	_ = pvShow[int]
}
