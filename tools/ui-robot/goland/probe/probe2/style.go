// Package probe2 triggers the GoLand style / redundancy / probable-bug inspections whose message texts the plugin guessed.
package probe2

import (
	"os"
	"errors"
	fmt "fmt"
	"strings"
	"time"
)

//no leading space here
var Snake_case_var = 1

// Wrong name in the comment of an exported function.
func Exported() {}

func NoDocExported() {}

var A, B = 1, 2

type Probe2Config struct {
	TimeoutMs time.Duration
	name      string
	n         int
}

func (this *Probe2Config) M1() {}
func (self Probe2Config) M2()  {}
func (_ Probe2Config) M3()     {}
func (c Probe2Config) M4()     {}
func (x Probe2Config) M5()     {}

func (c Probe2Config) SetName(v string) {
	c.name = v
	c = Probe2Config{}
}

func Generic[t any](x t) t { return x }

func Unused[T any]() {}

func do_thing(count_total int) int { return count_total }

func redundant(x int) int {
	if (x > 0) {
		return (x)
	} else {
		return -x
	}
}

func loops() {
	for true {
		break
	}
	y := 1;
	_ = y
	var n int = 1
	_ = n
	s := []int{}
	_ = s
	t := []int{1, 2,}
	_ = t
	fmt.Println(1, 2,)
	type point struct{ x, y int }
	pts := []point{point{1, 2}}
	_ = pts
	pc := Probe2Config{1, "a", 2}
	_ = pc
	strings := 1
	_ = strings
	new := 2
	_ = new
	var waitSeconds time.Duration = 3
	_ = waitSeconds
}

var ()

const ()

func errs() error {
	err := errors.New("Capitalized error.")
	if e, ok := err.(*os.PathError); ok {
		return e
	}
	return fmt.Errorf("another Error\n")
}

func deferGo() {
	defer recover()
	go panic("x")
}

const (
	A0 = iota
	A1 = iota
	A2
)

const (
	B0 = 1 << iota
	B1 = 1 << iota
	B2
)

const (
	C0 = iota
	C1 = 10
	C2 = iota
)

const (
	D0 = iota * 2
	D1
	D2 = iota * 2
)

func shadow(ch chan int) {
	x := 1
	if x > 0 {
		x := 2
		_ = x
	}
	_ = x
	select {
	case n := <-ch:
		_ = n
	default:
	}
	_ = strings.ToUpper("a")
}

// Probe2Config is used by Exported with [Generic] and time.Duration; see also os.PathError.
type Doc struct{}
