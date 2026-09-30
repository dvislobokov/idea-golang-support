package io.github.golangsupport

import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoFieldAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoFieldAlignmentTest {
    private val none: (String) -> String? = { null }

    private fun bodyOf(source: String): CharSequence {
        val structure = GoDeclarations.scan(source)
        val body = structure.declarations.first { it.name == "T" }.body!!
        return source.subSequence(body.startOffset + 1, body.endOffset - 1)
    }

    @Test fun sizesOfTypes() {
        assertEquals(1, GoFieldAlignment.layoutOf("bool", none)!!.size)
        assertEquals(16, GoFieldAlignment.layoutOf("string", none)!!.size)
        assertEquals(24, GoFieldAlignment.layoutOf("[]byte", none)!!.size)
        assertEquals(8, GoFieldAlignment.layoutOf("*Server", none)!!.size)
        assertEquals(8, GoFieldAlignment.layoutOf("map[string]int", none)!!.size)
        assertEquals(8, GoFieldAlignment.layoutOf("chan struct{}", none)!!.size)
        assertEquals(8, GoFieldAlignment.layoutOf("func(int) error", none)!!.size)
        assertEquals(12, GoFieldAlignment.layoutOf("[3]int32", none)!!.size)
        assertEquals(4, GoFieldAlignment.layoutOf("[3]int32", none)!!.align)
        assertEquals(0, GoFieldAlignment.layoutOf("struct{}", none)!!.size)
        assertEquals(24, GoFieldAlignment.layoutOf("time.Time", none)!!.size)
        assertNull(GoFieldAlignment.layoutOf("pkg.Unknown", none))
        assertNull(GoFieldAlignment.layoutOf("Local", none))
        assertEquals(8, GoFieldAlignment.layoutOf("Local", { if (it == "Local") "int64" else null })!!.size)
    }

    @Test fun structLayout() {
        val bool = GoFieldAlignment.Layout(1, 1)
        val int64 = GoFieldAlignment.Layout(8, 8)
        assertEquals(24, GoFieldAlignment.structLayout(listOf(bool, int64, bool)).size)
        assertEquals(16, GoFieldAlignment.structLayout(listOf(int64, bool, bool)).size)
        // a trailing zero-size field is padded
        assertEquals(16, GoFieldAlignment.structLayout(listOf(int64, GoFieldAlignment.Layout(0, 1))).size)
        assertEquals(8, GoFieldAlignment.structLayout(listOf(GoFieldAlignment.Layout(0, 1), int64)).size)
    }

    @Test fun reordersAndKeepsComments() {
        val source = """
            type T struct {
            	// Debug turns the log on.
            	Debug bool // trailing
            	Timeout int64
            	Name    string `json:"name"`
            	Verbose bool

            	done chan struct{}
            }
        """.trimIndent()
        val body = bodyOf(source)
        val result = GoFieldAlignment.analyze(body, none)!!
        assertEquals(48, result.currentSize)
        assertEquals(40, result.optimalSize)
        assertTrue(result.saves)
        assertEquals(
            "\n\tName    string `json:\"name\"`\n\tTimeout int64\n\tdone chan struct{}\n\t// Debug turns the log on.\n\tDebug bool // trailing\n\tVerbose bool\n",
            GoFieldAlignment.rewrite(body, result),
        )
    }

    @Test fun nothingToGain() {
        val result = GoFieldAlignment.analyze(bodyOf("type T struct {\n\tA int64\n\tB, C bool\n}"), none)!!
        assertEquals(16, result.currentSize)
        assertFalse(result.saves)
    }

    @Test fun multiNameFieldsAndEmbedded() {
        val others = "type Inner struct {\n\tA bool\n\tB int64\n}\ntype ID int32"
        val local = GoFieldAlignment.localTypes(GoDeclarations.scan(others), others)
        val result = GoFieldAlignment.analyze(bodyOf("type T struct {\n\tX, Y bool\n\tInner\n\tID ID\n}"), local)!!
        // Inner is 16 bytes aligned to 8: bool bool [pad 6] Inner(16) ID(4) [pad 4] = 32; Inner first: 16 + 4 + 1 + 1 -> 24
        assertEquals(32, result.currentSize)
        assertEquals(24, result.optimalSize)
    }

    @Test fun unknownAndMultilineFieldsAreLeftAlone() {
        assertNull(GoFieldAlignment.analyze(bodyOf("type T struct {\n\tA pkg.Thing\n\tB bool\n}"), none))
        assertNull(GoFieldAlignment.analyze(bodyOf("type T struct {\n\tA struct {\n\t\tX bool\n\t}\n\tB int64\n}"), none))
    }

    @Test fun closingBraceIndentIsKept() {
        val source = "type (\n\tT struct {\n\t\tA bool\n\t\tB int64\n\t}\n)"
        val body = bodyOf(source)
        val result = GoFieldAlignment.analyze(body, none)!!
        assertEquals("\n\t\tB int64\n\t\tA bool\n\t", GoFieldAlignment.rewrite(body, result))
    }
}
