package tf_test

import "testing"

func TestX(t *testing.T) {
	_ = /*no ref*/ private
	_ = /*no ref*/ helperT
	t./*ref GOROOT:testing/testing.go*/ Log("x")
}
