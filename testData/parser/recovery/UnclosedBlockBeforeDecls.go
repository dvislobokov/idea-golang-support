package p

func broken() {
	if x {
		y := 1
	// missing closing brace of the if
}

var after = 1

const c = 2

type T struct{ a int }

func ok() {}
