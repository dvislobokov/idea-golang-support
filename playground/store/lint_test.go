package store

import "testing"

func TestLeak(t *testing.T) {
	tests := []struct {
		name string
	}{
		// TODO: add test cases
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			Leak()
		})
	}
}
