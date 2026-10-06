package probe

type (
	bcE1 struct{ X int }
	bcE2 struct{ bcE1 }
	bcE3 struct{ bcE2 }
	bcE4 struct{ bcE2 }
	bcE5 struct {
		bcE3
		bcE4
	}
)

// want: ambiguous selector bcE5{}.X
var bcAmb2 = bcE5{}.X
