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
