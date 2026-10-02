package typemismatch

type MyInt int

func f() {
	var i int
	var s string
	i = <error descr="cannot use s (variable of type string) as int value in assignment">s</error>
	s = <error descr="cannot use 1 (untyped int constant) as string value in assignment">1</error>
	var i8 int8 = <error descr="cannot use 256 (untyped int constant) as int8 value in variable declaration (overflows)">256</error>
	var m MyInt = 3
	i = <error descr="cannot use m (variable of int type MyInt) as int value in assignment">m</error>
	i = <error descr="cannot use nil as int value in assignment">nil</error>
	_, _, _ = i, s, i8
	_ = string(<error descr="cannot convert 1.5 (untyped float constant) to type string">1.5</error>)
}
