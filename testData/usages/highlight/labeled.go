package h

func g() {
outer:
	for {
		for {
			if true {
				break outer
			}
			<caret>continue outer
		}
		break
	}
}
