package unusedimport

import (
	"fmt"
	<error descr="\"os\" imported and not used">"os"</error>
	<error descr="\"strings\" imported as str and not used">str "strings"</error>
	_ "embed"
)

func f() {
	fmt.Println()
}
