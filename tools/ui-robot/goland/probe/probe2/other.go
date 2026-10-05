package probe2

// Receiver name differs from the receivers in style.go: does GoLand compare across files?
func (y Probe2Config) M6() {}

func format(n int, s string) string {
	_ = n
	return fmt_sprintf("%d %s", 1)
}

func fmt_sprintf(f string, a ...any) string { return f }

//go:generate echo hi

type Tagged struct {
	Name  string `json:"Name"`
	Value int    `json:"value"`
}
