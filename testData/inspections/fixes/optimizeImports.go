package p

import (
	"strings"
	"os"
	"fmt"

	"errors"
	"bytes"
	"io"
)

func f() error {
	fmt.Println(strings.ToUpper("a"))
	var b bytes.Buffer
	_ = b
	return errors.New("x")
}
