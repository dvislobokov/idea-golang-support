package p

// Doc comment for F.
func F() {} // trailing

/* block comment */
var x = 1 /* inline */

// Group doc.
var (
	// a doc
	a = 1 // a line
	bbb = 2   // bbb line
)

func g() {
	// leading comment
	y := 1 // trailing y
	zzz := 2 // trailing zzz
	_, _ = y, zzz
	/*
		multi-line block
	*/
	// last comment
}
