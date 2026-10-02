package missingreturn

func ok() int {
	return 1
}

func okPanic() int {
	panic("x")
}

func okFor() int {
	for {
	}
}

func okSwitch(x int) int {
	switch x {
	case 1:
		return 1
	default:
		panic("no")
	}
}

func okIfElse(x int) int {
	if x > 0 {
		return 1
	} else {
		return 2
	}
}

func missing(x int) int {
	if x > 0 {
		return 1
	}
<error descr="missing return">}</error>

func missingForBreak() int {
	for {
		break
	}
<error descr="missing return">}</error>

func missingSwitchNoDefault(x int) int {
	switch x {
	case 1:
		return 1
	}
<error descr="missing return">}</error>

var lit = func() string {
<error descr="missing return">}</error>

func noResults() {
}
