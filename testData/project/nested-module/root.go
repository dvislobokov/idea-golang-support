package root

import (
	_ "example.com/root/lib"
	_ "example.com/root/nested/x" // unresolved: belongs to the nested module, which is not required
)
