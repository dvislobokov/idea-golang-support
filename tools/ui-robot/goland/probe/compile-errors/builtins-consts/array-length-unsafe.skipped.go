package probe

import "unsafe"

// Not reported: the length uses unsafe.Sizeof, which the plugin folds for gc/amd64 only; on another
// GOARCH a padding length could differ, so array-length errors are not reported for such lengths.
// want: invalid array length -int(unsafe.Sizeof(int32(0))) (constant -4 of type int)
var bcArrUnsafe [-int(unsafe.Sizeof(int32(0)))]byte
