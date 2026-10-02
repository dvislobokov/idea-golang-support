package main

import (
	"fmt"

	"example.com/simple/internal/util"
	"example.com/simple/pkg/lib"
	"golang.org/x/sync/errgroup"
)

func main() {
	var g errgroup.Group
	fmt.Println(util.Name, lib.Name, &g)
}
