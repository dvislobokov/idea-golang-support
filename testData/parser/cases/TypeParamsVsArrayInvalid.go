// Exercises invalid forms of "type Name [" disambiguation (GRAMMAR.md section C).
// Intentionally NOT valid Go: used for error recovery only, so gofmt and the type
// checker are expected to reject it. Each declaration is separate.
package cases

type B1[P, Q] struct{}
type B2[P any,, Q any] int
type B3[P any Q any] int
type B4[P any int
type B5[P *E |] int
type B6[~P any] int
type B7[P any, ] [
type B8 struct{}
