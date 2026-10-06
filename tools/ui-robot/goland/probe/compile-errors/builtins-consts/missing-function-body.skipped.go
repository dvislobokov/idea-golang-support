package probe

// Not reported: go/types accepts a body-less function (it may be implemented in assembly; GOROOT has
// many); the compiler reports it when the package has no .s files.
// want: missing function body
func bcNoBody()
