package probe

type (
	bcF6 struct{ X int }
	bcF7 bcF6
	bcF5 struct {
		bcF6
		bcF7
	}
)

// want: ambiguous selector bcF5{}.X
var bcAmb1 = bcF5{}.X
