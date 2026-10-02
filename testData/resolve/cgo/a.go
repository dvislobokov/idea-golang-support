package cgo

// #include <stdio.h>
import /*def*/ "C"

func f() {
	/*ref*/ C.puts(nil)
	_ = /*ref*/ C.int(0)
}
