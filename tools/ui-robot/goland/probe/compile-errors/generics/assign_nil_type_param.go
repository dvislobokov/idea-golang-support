package probe

type niIface interface{ m() }

func niUse[T niIface](x T) {
	// want: cannot use nil as T value in assignment
	x = nil
	_ = x
}
