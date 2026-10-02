package io.github.golangsupport

import io.github.golangsupport.format.GoTextDiff
import io.github.golangsupport.lang.GoIndentEngine
import io.github.golangsupport.templates.GoPackageNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoEditingTest {
    /** Formatted by gofmt: the engine has to give every line the level it already has. */
    private val formatted = """
package shop

import (
	"fmt"
	"os"
)

type Item struct {
	Name  string
	Price int
}

var prices = map[string]int{
	"tea": f(
		1,
		2,
	),
	"cup": 900,
}

func Total(items []Item, discount int) (total int, err error) {
	for _, item := range items {
		switch {
		case item.Price < 0:
			return 0, fmt.Errorf("negative price of %s", item.Name)
		case item.Price == 0, discount > 100:
			continue
		default:
			total += item.Price
		}
	}
	if total > 0 &&
		discount > 0 {
		total -= total * discount / 100
	}
	handle(func() {
		fmt.Println(total)
	})
	handle(func() {
		os.Exit(1)
	}, 5)
	select {
	case <-done:
		i++
	}
	text := `raw
  as it is
	and this`
	result := compute().
		Add(1)
	return total, nil
}
""".trimStart('\n')

    @Test fun everyLineOfFormattedCodeKeepsItsLevel() {
        var offset = 0
        val insideRawString = setOf("  as it is", "\tand this`")
        for (line in formatted.split('\n')) {
            if (line.isNotBlank()) {
                val expected = if (line in insideRawString) null else line.takeWhile { it == '\t' }.length
                assertEquals("line '$line'", expected, GoIndentEngine.levelOf(formatted, offset))
            }
            offset += line.length + 1
        }
    }

    @Test fun newLineAfterAnOpeningBrace() {
        val text = "func main() {\n"
        assertEquals(1, GoIndentEngine.levelOf(text, text.length))
        val nested = "func main() {\n\tif x {\n"
        assertEquals(2, GoIndentEngine.levelOf(nested, nested.length))
        val afterCase = "func main() {\n\tswitch x {\n\tcase 1:\n"
        assertEquals(2, GoIndentEngine.levelOf(afterCase, afterCase.length))
        val afterIncrement = "func main() {\n\ti++\n"
        assertEquals(1, GoIndentEngine.levelOf(afterIncrement, afterIncrement.length))
        val afterAssign = "func main() {\n\ttotal :=\n"
        assertEquals(2, GoIndentEngine.levelOf(afterAssign, afterAssign.length))
    }

    @Test fun minimalReplacement() {
        assertNull(GoTextDiff.minimal("same", "same"))
        val replacement = GoTextDiff.minimal("package a\n\nfunc  f() {}\n", "package a\n\nfunc f() {}\n")!!
        assertEquals("package a\n\nfunc f() {}\n", "package a\n\nfunc  f() {}\n".replaceRange(replacement.start, replacement.end, replacement.text))
        assertTrue(replacement.end - replacement.start <= 2 && replacement.text.length <= 1)
        // nothing in common, an insertion at the end, a removal at the start
        for ((old, new) in listOf("abc" to "xyz", "abc" to "abcd", "xabc" to "abc", "" to "a", "aaa" to "aa")) {
            val r = GoTextDiff.minimal(old, new)!!
            assertEquals(new, old.replaceRange(r.start, r.end, r.text))
        }
    }

    @Test fun packageOfANewFile() {
        assertEquals("store", GoPackageNames.forDirectory(listOf("store_test", "store"), "whatever", "app"))
        assertEquals("main", GoPackageNames.forDirectory(emptyList(), "api", "cmd"))
        assertEquals("myapp", GoPackageNames.forDirectory(emptyList(), "My-App", "src"))
        assertEquals("main", GoPackageNames.forDirectory(emptyList(), "123", "src"))
    }

    @Test fun fileIcons() {
        assertEquals(GoIcons.Module, GoIcons.forFile("go.mod"))
        assertEquals(GoIcons.Workspace, GoIcons.forFile("go.work"))
        assertEquals(GoIcons.Sum, GoIcons.forFile("go.work.sum"))
        assertEquals(GoIcons.TestFile, GoIcons.forFile("order_test.go"))
        assertEquals(GoIcons.Generated, GoIcons.forFile("api.pb.go"))
        assertEquals(GoIcons.Generated, GoIcons.forFile("zz_generated.deepcopy.go"))
        assertEquals(GoIcons.Template, GoIcons.forFile("page.gohtml"))
        assertEquals(GoIcons.Config, GoIcons.forFile(".golangci.yml"))
        assertEquals(GoIcons.Binary, GoIcons.forFile("__debug_bin123.exe"))
        // a plain Go file has the icon of its file type, other files are not ours
        assertNull(GoIcons.forFile("order.go"))
        assertNull(GoIcons.forFile("config.yaml"))
    }
}
