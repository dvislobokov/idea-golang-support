package tv_test

import (
	"testing"

	"example.com/testvariant/tv"
)

func TestD(t *testing.T) {
	d := &tv.D{}
	if !d.Synced() || tv.Hidden() != 1 {
		t.Fatal()
	}
}
