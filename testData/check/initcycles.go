package initcycles

// Initialization cycles through functions and methods (go/types testdata check/init2.go, init1.go).

func f1() int { _ = x1; return 0 }

var x1 /* ERROR "initialization cycle" */ = f1

func f2() int { _ = x2; return 0 }

var x2 /* ERROR "initialization cycle" */ = f2()

type T3 int

func (T3) m() int { _ = x3; return 0 }

var x3 /* ERROR "initialization cycle" */ = T3.m

type T4 int

func (T4) m() int { _ = x4; return 0 }

var x4 /* ERROR "initialization cycle" */ = T4.m(0)

type T5 struct{}

func (T5) m() int { _ = x5; return 0 }

var x5 /* ERROR "initialization cycle" */ = T5{}.m()

var s0 /* ERROR "initialization cycle: s0 refers to itself" */ = s0

var a0 /* ERROR "initialization cycle for a0" */ = b0
var b0 = a0

// no cycle: a function that does not run at initialization through a func literal stored in a variable
var ok = func() int { return 1 }()
