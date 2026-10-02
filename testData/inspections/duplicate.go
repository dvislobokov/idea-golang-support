package duplicate

func f(a int, <error descr="a redeclared in this block">a</error> string) {
	x := 1
	var <error descr="x redeclared in this block">x</error> int
	_ = x
	y := 2
	y <error descr="no new variables on left side of :=">:=</error> 3
	_ = y
}

func g() {}

func <error descr="g redeclared in this block">g</error>() {}
