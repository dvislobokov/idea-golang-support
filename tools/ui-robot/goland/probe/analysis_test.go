package probe

import (
	"fmt"
	"testing"
)

func TestFactorial(t *testing.T) {
	if Factorial(5) != 120 {
		t.Fatal("factorial")
	}
	// PROBE:test

}

func TestSum(t *testing.T) {
	tests := []struct {
		name string
		in   []int
		want int
	}{
		{"empty", nil, 0},
		{"two", []int{1, 2}, 3},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Sum(tt.in); got != tt.want {
				t.Errorf("Sum() = %v, want %v", got, tt.want)
			}
		})
	}
}

func BenchmarkFactorial(b *testing.B) {
	for i := 0; i < b.N; i++ {
		Factorial(10)
	}
}

func ExampleFactorial() {
	fmt.Println(Factorial(3))
	// Output: 6
}

func FuzzLoad(f *testing.F) {
	f.Add("abc")
	f.Fuzz(func(t *testing.T, s string) {
		_, _ = load(s)
	})
}
