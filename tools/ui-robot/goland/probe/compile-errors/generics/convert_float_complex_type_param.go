package probe

type cvFloat interface{ ~float32 | ~float64 }

type cvComplex interface{ ~complex64 | ~complex128 }

func cvConv[X, T cvFloat | cvComplex](x X) T {
	// want: cannot convert x (variable of type X constrained by cvFloat | cvComplex) to type T: cannot convert float32 (in X) to type complex64 (in T)
	return T(x)
}
