package probe

type bcI interface{ m() int }

func bcCmpI(i bcI) bool {
	// want: invalid operation: i == 0 (mismatched types bcI and untyped int)
	return i == 0
}
