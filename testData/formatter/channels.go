package p

func f(in <-chan int, out chan<- int, both chan int) {
v := <- in
out<-v
both <- <-in
var c chan<- chan int
var d <-chan <-chan int
_, _ = c, d
go func() { both <- 1 }()
defer close(out)
}
