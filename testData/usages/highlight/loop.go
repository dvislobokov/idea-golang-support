package h

func g(ch chan int) {
	<caret>select {
	case <-ch:
		break
	default:
	}
	for {
		break
	}
}
