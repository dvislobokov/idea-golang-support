// Stub coverage: grouped consts with iota and implicit repetition, typed consts, vars.
package consts

type Weekday int

const (
	Sunday Weekday = iota
	Monday
	Tuesday
	_
	Thursday
)

const (
	KB = 1 << (10 * (iota + 1))
	MB
	a, b = iota, iota * 2
)

const Single = "single"

var (
	x, y    = 1, 2
	z       int
	private = struct{ n int }{n: 1}
)

var Exported, unexported float64

func f() {
	const inner = 1
	var local = 2
	_ = local
}
