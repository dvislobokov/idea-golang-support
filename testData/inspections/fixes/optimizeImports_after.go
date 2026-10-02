package p

import (
	"fmt"
	"strings"

	"bytes"
	"errors"
)

func f() error {
	fmt.Println(strings.ToUpper("a"))
	var b bytes.Buffer
	_ = b
	return errors.New("x")
}
