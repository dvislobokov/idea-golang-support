package operators

type MyInt int

func f() {
	var i int
	var s string
	var b bool
	var fl float64
	var m MyInt
	_ = i + 1
	_ = i + s // ERROR "invalid operation: i + s (mismatched types int and string)"
	_ = i + m // ERROR "invalid operation: i + m (mismatched types int and MyInt)"
	_ = s + "x"
	_ = b + b // ERROR "invalid operation: operator + not defined on b (variable of type bool)"
	_ = s - s // ERROR "invalid operation: operator - not defined on s (variable of type string)"
	_ = fl % 2 // ERROR "invalid operation: operator % not defined on fl (variable of type float64)"
	_ = i % 2
	_ = i & 1
	_ = fl & 1 // ERROR "invalid operation: operator & not defined on fl (variable of type float64)"
	_ = b && true
	_ = i && b // ERROR "invalid operation: operator && not defined on i (variable of type int)"
	_ = i == 1
	_ = i == s // ERROR "invalid operation: i == s (mismatched types int and string)"
	_ = s < "a"
	_ = b < true // ERROR "invalid operation: b < true (operator < not defined on bool)"
	var sl []int
	_ = sl == nil
	_ = sl == sl // ERROR "invalid operation: sl == sl (slice can only be compared to nil)"
	var fn func()
	_ = fn == nil
	_ = fn == fn // ERROR "invalid operation: fn == fn (func can only be compared to nil)"
	_ = i << 2
	_ = i << s // ERROR "invalid operation: shift count s (variable of type string) must be integer"
	_ = fl << 1 // ERROR "invalid operation: shifted operand fl (variable of type float64) must be integer"
	_ = -i
	_ = -s // ERROR "invalid operation: operator - not defined on s (variable of type string)"
	_ = !b
	_ = !i // ERROR "invalid operation: operator ! not defined on i (variable of type int)"
	_ = ^i
	_ = ^fl // ERROR "invalid operation: operator ^ not defined on fl (variable of type float64)"
	var p *int
	_ = *p
	_ = *i // ERROR "invalid operation: cannot indirect i (variable of type int)"
	var ch chan int
	_ = <-ch
	_ = <-i // ERROR "invalid operation: cannot receive from non-channel i (variable of type int)"
	var sch chan<- int
	_ = <-sch // ERROR "invalid operation: cannot receive from send-only channel sch (variable of type chan<- int)"
	ch <- 1
	i <- 1 // ERROR "invalid operation: cannot send to non-channel i (variable of type int)"
	var rch <-chan int
	rch <- 1 // ERROR "invalid operation: cannot send to receive-only channel rch (variable of type <-chan int)"
	ch <- "a" // ERROR `cannot use "a" (untyped string constant) as int value in send`
	i++
	s++ // ERROR "invalid operation: s++ (non-numeric type string)"
	i += 1
	i += "a" // ERROR `invalid operation: i += "a" (mismatched types int and untyped string)`
	var x any = 1
	_ = x == 1
	_ = x == i
	var e error
	_ = e == nil
	_ = i + 1.5 // ERROR "1.5 (untyped float constant) truncated to int"
	_ = fl + 1
	_ = i
}
