package p

import "time"

type Celsius float64

func takes(c Celsius, d time.Duration) {}

func f(i int, x float64) float64 {
	var c Celsius = Celsius(x)
	takes(c, i)
	return i
}
