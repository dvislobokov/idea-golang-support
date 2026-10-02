// Exercises "type Name [" array versus type parameter list (GRAMMAR.md section C).
// Every valid row of the table, one declaration each. Invalid rows are in
// TypeParamsVsArrayInvalid.go. Type-checks.
package cases

const (
	N = 4
	P = 3
	M = 2
)

type (
	C interface{ ~int }
	E interface{ ~int | ~string }
	F interface{ ~int | ~string }
	Q struct{}
)

type A1 [N]int
type A2 [P]int
type A3 [P * M]int
type A4[P *Q,] struct{}
type A5[P *[]int] struct{}
type A6[P []int] struct{}
type A6b[P E] struct{}
type A7[P any] struct{}
type A8[P, Q any] struct{}
type A9[P *Q | F | ~int] struct{}
type A10[P interface{ ~int }] struct{}
type A11[P, Q interface{ ~int }, R any] struct{}
type A12 []int
type A13 [N + 1]int
type A14 [len("ab")]int
type A15 [2][3]int
type A16[P C] []P
