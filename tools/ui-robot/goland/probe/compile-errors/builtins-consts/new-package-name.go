package probe

import "unsafe"

var bcPtrSize = unsafe.Sizeof(uintptr(0))

func bcNewPkg() {
	// want: use of package unsafe not in selector
	_ = new(unsafe)
}
