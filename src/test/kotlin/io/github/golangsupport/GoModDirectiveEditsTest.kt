package io.github.golangsupport

import io.github.golangsupport.mod.GoModDirectiveEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure edits behind the go.mod intentions Merge a group of directives / Merge all directives / Merge directive up. */
class GoModDirectiveEditsTest {
    private fun lines(text: String) = text.split('\n')
    private fun text(lines: List<String>?) = lines?.joinToString("\n")

    @Test fun directivesOfLinesAndBlocks() {
        val ds = GoModDirectiveEdits.directives(lines("module m\n\ngo 1.22\n\nrequire (\n\ta v1 // c\n)\nrequire b v2\nreplace c => ../c\n// require x v0\n"))
        assertEquals(listOf("module", "go", "require", "require", "replace"), ds.map { it.verb })
        assertEquals(GoModDirectiveEdits.Directive("require", 4, 6, true), ds[2])
        assertEquals(GoModDirectiveEdits.Directive("require", 7, 7, false), ds[3])
    }

    @Test fun mergeGroupOfConsecutiveLinesKeepsComments() {
        val before = "module m\n\nrequire a.com/x v1.0.0\nrequire b.com/y v1.2.0 // indirect\nrequire c.com/z v0.1.0\n\nrequire d.com/w v1.0.0\n"
        val after = "module m\n\nrequire (\n\ta.com/x v1.0.0\n\tb.com/y v1.2.0 // indirect\n\tc.com/z v0.1.0\n)\n\nrequire d.com/w v1.0.0\n"
        assertEquals(after, text(GoModDirectiveEdits.mergeGroup(lines(before), 3)))
        assertEquals(after, text(GoModDirectiveEdits.mergeGroup(lines(before), 2)))
        // alone after a blank line, on a block, on another verb, on module: nothing
        assertNull(GoModDirectiveEdits.mergeGroup(lines(before), 6))
        assertNull(GoModDirectiveEdits.mergeGroup(lines(before), 0))
        assertNull(GoModDirectiveEdits.mergeGroup(lines("require a v1\nreplace b => ../b\n"), 0))
        assertNull(GoModDirectiveEdits.mergeGroup(lines("module m\nmodule n\n"), 0))
    }

    @Test fun mergeGroupOfReplaceAndExclude() {
        assertEquals("replace (\n\ta => ../a\n\tb v1 => c v2\n)\n", text(GoModDirectiveEdits.mergeGroup(lines("replace a => ../a\nreplace b v1 => c v2\n"), 1)))
        assertEquals("exclude (\n\ta v1\n\ta v2\n)", text(GoModDirectiveEdits.mergeGroup(lines("exclude a v1\nexclude a v2"), 0)))
    }

    @Test fun mergeAllLinesAndBlocksOfTheVerb() {
        val before = "module m\n\nrequire a v1\n\nrequire (\n\tb v2\n\n\tc v3 // indirect\n)\n\nreplace x => ../x\n\nrequire d v4\n"
        val after = "module m\n\nrequire (\n\ta v1\n\tb v2\n\n\tc v3 // indirect\n\td v4\n)\n\nreplace x => ../x\n"
        assertEquals(after, text(GoModDirectiveEdits.mergeAll(lines(before), 2)))
        // from inside a block too; on replace (one directive) and on module: nothing
        assertEquals(after, text(GoModDirectiveEdits.mergeAll(lines(before), 5)))
        assertNull(GoModDirectiveEdits.mergeAll(lines(before), 10))
        assertNull(GoModDirectiveEdits.mergeAll(lines(before), 0))
    }

    @Test fun mergeAllKeepsTheHeaderOfAFirstBlock() {
        val before = "require ( // direct\n\ta v1\n)\n\nrequire (\n\tb v2 // indirect\n)\n"
        assertEquals("require ( // direct\n\ta v1\n\tb v2 // indirect\n)\n", text(GoModDirectiveEdits.mergeAll(lines(before), 0)))
    }

    @Test fun mergeUpIntoTheBlockAbove() {
        val before = "module m\n\nrequire (\n\ta v1\n)\n\nrequire b v2 // indirect\n"
        assertEquals("module m\n\nrequire (\n\ta v1\n\tb v2 // indirect\n)\n", text(GoModDirectiveEdits.mergeUp(lines(before), 6)))
        assertEquals("require (\n\ta v1\n\tb v2\n)\nreplace x => ../x\n", text(GoModDirectiveEdits.mergeUp(lines("require (\n\ta v1\n)\nrequire b v2\nreplace x => ../x\n"), 3)))
        // above is another verb, a line, or something in between: nothing
        assertNull(GoModDirectiveEdits.mergeUp(lines("replace (\n\tx => ../x\n)\nrequire b v2\n"), 3))
        assertNull(GoModDirectiveEdits.mergeUp(lines("require a v1\nrequire b v2\n"), 1))
        assertNull(GoModDirectiveEdits.mergeUp(lines("require (\n\ta v1\n)\n// note\nrequire b v2\n"), 4))
    }

    @Test fun unterminatedBlockStopsTheScan() {
        assertEquals(listOf("module"), GoModDirectiveEdits.directives(lines("module m\nrequire (\n\ta v1\nrequire b v2\n")).map { it.verb })
    }

    @Test fun smallestChange() {
        val (from, to, replacement) = GoModDirectiveEdits.change(lines("a\nb\nc\nd"), lines("a\nX\nY\nd"))
        assertEquals(1, from)
        assertEquals(3, to)
        assertEquals(listOf("X", "Y"), replacement)
        assertEquals(Triple(2, 2, listOf("z")), GoModDirectiveEdits.change(listOf("a", "b"), listOf("a", "b", "z")))
    }
}
