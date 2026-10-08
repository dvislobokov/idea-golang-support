package main

import (
	"fmt"

	"example.com/missingpkg/util"
	"github.com/labstack/echo/v5"
	"nosuch/stdlike"
)

import unused "example.com/missingpkg/nothere"

func main() {
	e := echo.New()
	e.Use()
	stdlike.Run()
	var x echo.Context
	_ = x
	fmt.Println(util.Name)
}
