// Not gofmt-clean on purpose: parenthesised and forced forms from docs/GRAMMAR.md section C.
package cases

type A1[P (E)] struct{}          // array length P(E)
type A2[P (interface{})] int     // generic: paren'd type element
type A3[P (E),] int              // generic: forced by comma
type A4[P *C,] int               // generic: forced by comma
type A5[P *C | ~D] int           // generic: union term is a type element
type A6[P *C | D] int            // array length expression
type A7[P *[]int] int            // generic
type A8[P any | int] int         // generic (ident after name)
type A9[N] int                   // array
type A10[P * 2] int              // array
