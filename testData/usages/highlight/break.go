package h

func g() {
	for i := 0; i < 3; i++ {
		switch i {
		case 1:
			break
		}
		if i == 2 {
			<caret>break
		}
		continue
	}
}
