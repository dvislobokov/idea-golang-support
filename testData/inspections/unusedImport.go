package unusedimport

import (
	"fmt"
	<warning descr="\"os\" imported and not used">"os"</warning>
	<warning descr="\"strings\" imported as str and not used">str "strings"</warning>
	_ "embed"
)

func f() {
	fmt.Println()
}
