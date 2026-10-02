package p

func f(x int, v any, ch chan int, done chan struct{}) int {
	switch x {
	case 1, 2:
		return 1
	case 3:
		fallthrough
	default:
		x++
	}
	switch t := v.(type) {
	case int:
		_ = t
	case string, []byte:
	}
	switch {
	case x > 0:
		return x
	}
	select {
	case v := <-ch:
		return v
	case ch <- 1:
	case <-done:
	default:
	}
	select {}
}
