package probe

type ppBar struct{ Baz int }

type ppFoo struct{ *ppBar }

func ppUse() {
	// want: invalid implicit pointer indirection to reach Baz
	_ = ppFoo{Baz: 1}
}
