package probe

type pbBar struct{ Baz int }

type pbFoo struct{ pbBar }

func pbUse() {
	// want: cannot specify embedded field pbBar and enclosed promoted field Baz
	_ = pbFoo{Baz: 1, pbBar: pbBar{}}
}
