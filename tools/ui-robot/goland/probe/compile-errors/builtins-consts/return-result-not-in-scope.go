package probe

func bcResults() (a, b int) {
	{
		type a int
		// want: result parameter a not in scope at return
		return
	}
}
