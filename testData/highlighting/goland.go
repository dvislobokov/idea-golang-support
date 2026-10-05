package goland

import (
	"fmt"
	"testing"
)

// PublicInterface is exported.
type PublicInterface interface {
	PublicFunc() int
	privateFunc() int
}

type privateInterface interface{ PublicFunc() int }

// PublicStruct embeds nothing; see [privateStruct] and [fmt.Println].
type PublicStruct struct {
	PublicField  int
	privateField int    `json:"private_field,omitempty" text`
	Escaped      string "xml:\"name\""
	Call         func()
	call         func()
}

type privateStruct struct{ PublicField int }

type demoInt int

const (
	PublicConst  = 1
	privateConst = 2
)

var (
	PublicVar  = func() {}
	privateVar = func() {}
)

func (ps PublicStruct) PublicFunc() int { return ps.privateField }

func (ps *privateStruct) privateFunc() int { return ps.PublicField }

func Exported(pi PublicInterface, li privateInterface, d demoInt, f func()) error {
	const localConst = PublicConst + privateConst
	var err error
	a, err := pi.PublicFunc(), nil
	if b, c := li.PublicFunc(), d; b > 0 {
		_, _ = b, c
	}
	for i := range 3 {
		_ = i
	}
	switch x := a; {
	case x > 0:
		y := x
		_ = y
	}
	s := PublicStruct{PublicField: 1}
	s.Call()
	s.call()
	PublicVar()
	privateVar()
	f()
	local := func() {}
	local()
	_ = len("ab\n\x4\u00e9\U0001F600\101\400\q")
	_ = '\''
	fmt.Printf("%d %-10s %[1]v %%\n", a, "x")
	_ = fmt.Sprintf(`%q`, localConst)
	fmt.Println("%d is not a format here")
	_, _ = true, nil
	return err
}

func helper(t *testing.T) {
	t.Errorf("want %v", t)
}
