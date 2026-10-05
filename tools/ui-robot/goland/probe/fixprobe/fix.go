// Package fixprobe holds patterns the Go fix (modernize) inspections rewrite, for the "Update syntax" lens of GoLand.
package fixprobe

import (
	"fmt"
	"sort"
	"strings"
)

func Old(s []int, a, b int, name string) int {
	var x interface{} = 1
	_ = x
	for i := 0; i < len(s); i++ {
		fmt.Println(s[i])
	}
	for i := 0; i < 10; i++ {
		fmt.Println(i)
	}
	m := a
	if b < m {
		m = b
	}
	sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })
	if strings.HasPrefix(name, "go") {
		name = strings.TrimPrefix(name, "go")
	}
	_ = name
	return m
}

func Sprintf(n int) string {
	return fmt.Sprintf("%d %s", n)
}
