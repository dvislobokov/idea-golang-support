// Exercises statements: select with send/recv/`v, ok := <-c`, goto, labeled
// break/continue, defer/go, fallthrough, switch with init, if-else chains. Type-checks.
package cases

func stmts(c, d chan int, n int) int {
	select {
	case c <- 1:
	case v := <-d:
		_ = v
	case v, ok := <-c:
		_, _ = v, ok
	case <-d:
	default:
	}
	var v int
	var ok bool
	select {
	case v, ok = <-c:
	}
	_, _ = v, ok
	defer func() { recover() }()
	go func(x int) {}(n)
	defer close(c)
loop:
	for i := 0; i < n; i++ {
		for {
			if i > 2 {
				continue loop
			}
			break loop
		}
	}
	switch x := n * 2; {
	case x > 1:
		fallthrough
	case x > 0:
		n++
	default:
	}
	if n > 1 {
		n = 1
	} else if n > 0 {
		n = 2
	} else {
		n = 3
	}
	goto end
end:
	return n
}
