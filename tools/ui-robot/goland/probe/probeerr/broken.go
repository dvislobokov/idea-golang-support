// Package probeerr has deliberate problems so that GoLand's inspections fire (docs/goland-analysis). It does not compile on purpose.
package probeerr

import (
	"fmt"
	"os"
	"strings"
)

type Reader interface {
	Read() string
}

type impl struct{}

func (*impl) Read() string { return "" }

func get() Reader {
	var p *impl
	return p
}

func Broken(items []string) int {
	unused := 42
	os.Remove("tmp")
	fmt.Printf("%d items\n", "many")
	x := 1
	x = x
	for _, it := range items {
		f, _ := os.Open(it)
		defer f.Close()
	}
	if get() == nil {
		return 0
	}
	missing(items)
	return len(items)
	fmt.Println("unreachable")
}

type Config struct {
	Name string `json:name`
	Port int    `json:"port" json:"p"`
}
