package probe

type psNumber interface{ ~int | ~float64 }

func psNum[A psNumber, B any](a A, b B) {}

func psUse() {
	// want: string does not satisfy psNumber (string missing in ~int | ~float64)
	psNum[string]("a", 1)
}
