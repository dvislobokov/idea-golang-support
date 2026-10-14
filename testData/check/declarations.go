package declarations

import "unsafe"

const ca /* ERROR "initialization cycle" */ = ca

var va /* ERROR "initialization cycle" */ = func() int { return va }()

var vb /* ERROR "initialization cycle" */ = fb()

func fb() int { return vb }

var ok1 = fok()

func fok() int { return 1 }

type I interface{}
type P *int
type UP unsafe.Pointer

type S struct {
	* /* ERROR "cannot be a pointer to an interface" */ I
	P /* ERROR "cannot be a pointer" */
	UP /* ERROR "cannot be unsafe.Pointer" */
}

func (I /* ERROR "invalid receiver type I (cannot be an interface)" */) m() {}
func (P /* ERROR "invalid receiver type P (cannot be a pointer)" */) m() {}
func (int /* ERROR "cannot define new methods on non-local type int" */) m() {}

func init /* ERROR "func init must have no arguments and no return values" */ (int) {}

type X struct{ F int }
type A struct{ X }
type B struct{ X }
type AB struct {
	A
	B
}

var _ = AB{}.F /* ERROR "ambiguous selector AB{}.F" */

// Comparisons with nil: only what has nil (go/types testdata spec/comparisons.go).
type nilS struct{ x int }

func nilCompare(b bool, a [2]int, s nilS, p *int, f func(), sl []int, m map[int]int, i interface{}) {
	_ = b == nil /* ERROR "invalid operation: b == nil (mismatched types bool and untyped nil)" */
	_ = a == nil /* ERROR "mismatched types [2]int and untyped nil" */
	_ = s == nil /* ERROR "mismatched types nilS and untyped nil" */
	_ = p == nil
	_ = f == nil
	_ = sl == nil
	_ = m == nil
	_ = i == nil
}

// A field where a method is wanted (go/types testdata fixedbugs/issue51025.go).
type fieldNotMethodI interface{ m() }
type fieldNotMethodT struct{ m func() }

func fieldNotMethod(t fieldNotMethodT) {
	var i fieldNotMethodI
	i = t // ERROR "fieldNotMethodT does not implement fieldNotMethodI (fieldNotMethodT.m is a field, not a method)"
	_ = i
}

// A pointer method on a value that is not addressable (go/types testdata fixedbugs/issue28251.go).
type ptrMethodT struct{}

func (*ptrMethodT) m() {}

func ptrMethod() {
	ptrMethodT{}.m /* ERROR "cannot call pointer method m on ptrMethodT" */ ()
	var v ptrMethodT
	v.m()
}
