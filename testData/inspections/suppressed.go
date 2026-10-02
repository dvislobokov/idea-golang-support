//noinspection GoUnusedLabel

package suppressed

import (
	"fmt"
	//noinspection GoUnusedImport
	"os"
)

//noinspection GoUnusedVariable
func f() {
	x := 1
}

func g() {
	//noinspection GoUnusedVariable,GoTypeMismatch
	var y int = "a"
	//noinspection ALL
	z := 2
	<warning descr="declared and not used: w">w</warning> := 3
L:
	for {
		break
	}
	fmt.Println()
}
