package store

import (
	"os"
	"testing"
)

func TestTotal(t *testing.T) {
	os.Getenv("hello")
	tests := []struct {
		name  string
		items []Item
		want  int
	}{
		{name: "empty", want: 0},
		{name: "two items", items: []Item{{Price: 100, Quantity: 2}, {Price: 50, Quantity: 1}}, want: 250},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			order := NewOrder("EUR")
			for _, item := range tt.items {
				order.Add(item)
			}
			if got := order.Total(); got != tt.want {
				t.Errorf("Total() = %d, want %d", got, tt.want)
			}
		})
	}
}

func TestValidate(t *testing.T) {
	if err := NewOrder("EUR").Validate(); err != ErrEmpty {
		t.Fatalf("Validate() = %v, want ErrEmpty", err)
	}
}

// Fails on purpose: the test tree needs something red.
func TestFailing(t *testing.T) {
	t.Log("about to fail")
	t.Errorf("expected failure to see how the tree shows it")
}

func TestSkipped(t *testing.T) {
	t.Skip("not today")
}

func BenchmarkTotal(b *testing.B) {
	order := NewOrder("EUR")
	order.Add(Item{Price: 10, Quantity: 3})
	for b.Loop() {
		order.Total()
	}
}
