// Command pprofdemo serves net/http/pprof on localhost:6060 and keeps some goroutines and memory busy: the program to try
// the pprof buttons of the Go Monitor on (Run it, then pick it in Go Monitor).
package main

import (
	"log"
	"net/http"
	_ "net/http/pprof"
	"runtime"
	"sync"
	"time"
)

func main() {
	// mutex and block profiles are recorded only when switched on
	runtime.SetMutexProfileFraction(5)
	runtime.SetBlockProfileRate(1)
	var mu sync.Mutex
	var cache [][]byte
	for range 8 {
		go func() {
			for {
				mu.Lock()
				cache = append(cache, make([]byte, 64<<10))
				if len(cache) > 200 {
					cache = cache[100:]
				}
				mu.Unlock()
				time.Sleep(20 * time.Millisecond)
			}
		}()
	}
	log.Println("pprof on http://localhost:6060/debug/pprof/")
	log.Fatal(http.ListenAndServe("localhost:6060", nil))
}
