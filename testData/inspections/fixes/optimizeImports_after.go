package p

import (
	"bytes"
	"errors"
	"fmt"
	"strings"
)

func f() error {
	fmt.Println(strings.ToUpper("a"))
	var b bytes.Buffer
	_ = b
	return errors.New("x")
}
