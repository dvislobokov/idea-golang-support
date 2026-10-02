package p

func compute() int { return 1 }

func f() {
	<caret>x := compute() + 1
}
