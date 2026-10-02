package universe

func f() {
	var s []int
	_ = /*ref GOROOT:builtin/builtin.go*/ len(s)
	_ = /*ref GOROOT:builtin/builtin.go*/ append(s, 1)
	var e /*ref GOROOT:builtin/builtin.go*/ error
	_ = e
	var b /*ref GOROOT:builtin/builtin.go*/ byte
	_ = b
	_ = /*ref GOROOT:builtin/builtin.go*/ true
	_ = /*ref GOROOT:builtin/builtin.go*/ nil
	var a /*ref GOROOT:builtin/builtin.go*/ any
	_ = a
}

type /*def*/ string int

func g() /*ref*/ string { return 0 }

func shadow() {
	/*def*/ len := 3
	_ = /*ref*/ len
}
