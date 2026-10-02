package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoFieldAlignment
import io.github.golangsupport.lang.GoStructPsi
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStructType

/** The layout of a struct with the sizes of the types of its fields from the PSI, and the order that wastes the least. */
class GoFieldAlignmentTest : BasePlatformTestCase() {
    private var files = 0

    /** The struct `T` of [source], in a package of its own. */
    private fun struct(source: String): GoStructType {
        val file = myFixture.addFileToProject("p${files++}/t.go", "package p\n\n$source\n") as GoFile
        return GoStructPsi.structOf(file.types.first { it.name == "T" })!!
    }

    /** The text between the braces of [struct]. */
    private fun body(struct: GoStructType): String = struct.text.substring(struct.lbrace!!.startOffsetInParent + 1, struct.rbrace!!.startOffsetInParent)

    fun testStructLayout() {
        val bool = GoFieldAlignment.Layout(1, 1)
        val int64 = GoFieldAlignment.Layout(8, 8)
        assertEquals(24, GoFieldAlignment.structLayout(listOf(bool, int64, bool)).size)
        assertEquals(16, GoFieldAlignment.structLayout(listOf(int64, bool, bool)).size)
        // a trailing zero-size field is padded
        assertEquals(16, GoFieldAlignment.structLayout(listOf(int64, GoFieldAlignment.Layout(0, 1))).size)
        assertEquals(8, GoFieldAlignment.structLayout(listOf(GoFieldAlignment.Layout(0, 1), int64)).size)
    }

    fun testReordersAndKeepsComments() {
        val struct = struct(
            "type T struct {\n\t// Debug turns the log on.\n\tDebug bool // trailing\n\tTimeout int64\n\tName    string `json:\"name\"`\n\tVerbose bool\n\n\tdone chan struct{}\n}",
        )
        val result = GoFieldAlignment.analyze(struct)!!
        assertEquals(48, result.currentSize)
        assertEquals(40, result.optimalSize)
        assertTrue(result.saves)
        assertEquals(
            "\n\tName    string `json:\"name\"`\n\tTimeout int64\n\tdone chan struct{}\n\t// Debug turns the log on.\n\tDebug bool // trailing\n\tVerbose bool\n",
            GoFieldAlignment.rewrite(body(struct), result),
        )
    }

    fun testNothingToGain() {
        val result = GoFieldAlignment.analyze(struct("type T struct {\n\tA int64\n\tB, C bool\n}"))!!
        assertEquals(16, result.currentSize)
        assertFalse(result.saves)
    }

    fun testMultiNameFieldsAndTypesOfThePackage() {
        val result = GoFieldAlignment.analyze(struct("type Inner struct {\n\tA bool\n\tB int64\n}\n\ntype ID int32\n\ntype T struct {\n\tX, Y bool\n\tInner\n\tID ID\n}"))!!
        // Inner is 16 bytes aligned to 8: bool bool [pad 6] Inner(16) ID(4) [pad 4] = 32; Inner first: 16 + 4 + 1 + 1 -> 24
        assertEquals(32, result.currentSize)
        assertEquals(24, result.optimalSize)
    }

    fun testUnknownAndMultilineFieldsAreLeftAlone() {
        assertNull(GoFieldAlignment.analyze(struct("type T struct {\n\tA pkg.Thing\n\tB bool\n}")))
        assertNull(GoFieldAlignment.analyze(struct("type T struct {\n\tA struct {\n\t\tX bool\n\t}\n\tB int64\n}")))
    }

    fun testClosingBraceIndentIsKept() {
        val struct = struct("type (\n\tT struct {\n\t\tA bool\n\t\tB int64\n\t}\n)")
        val result = GoFieldAlignment.analyze(struct)!!
        assertEquals("\n\t\tB int64\n\t\tA bool\n\t", GoFieldAlignment.rewrite(body(struct), result))
    }
}
