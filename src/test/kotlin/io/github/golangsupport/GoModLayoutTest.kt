package io.github.golangsupport

import io.github.golangsupport.mod.GoModDirectiveEdits
import io.github.golangsupport.mod.GoModLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure planners of VgoRequireDirectivesMerge, VgoMigrateFromReplacesToWorkspace and VgoUnresolvedIgnorePath. */
class GoModLayoutTest {
    private fun lines(text: String) = text.split('\n')
    private fun text(lines: List<String>?) = lines?.joinToString("\n")

    @Test fun tidyLayoutsAreGrouped() {
        assertTrue(GoModDirectiveEdits.requiresAreGrouped(lines("module m\n")))
        assertTrue(GoModDirectiveEdits.requiresAreGrouped(lines("module m\nrequire a v1\n")))
        assertTrue(GoModDirectiveEdits.requiresAreGrouped(lines("module m\nrequire (\n\ta v1\n\tb v2 // indirect\n)\n")))
        assertTrue(GoModDirectiveEdits.requiresAreGrouped(lines("module m\nrequire (\n\ta v1\n\tc v3\n)\n\nrequire (\n\tb v2 // indirect\n)\n")))
        assertTrue(GoModDirectiveEdits.requiresAreGrouped(lines("module m\nrequire a v1\n\nrequire b v2 // indirect\n")))
    }

    @Test fun otherLayoutsAreNot() {
        assertFalse(GoModDirectiveEdits.requiresAreGrouped(lines("module m\nrequire a v1\nrequire c v3\n")))
        assertFalse(GoModDirectiveEdits.requiresAreGrouped(lines("module m\nrequire (\n\ta v1\n\tb v2 // indirect\n)\nrequire c v3\n")))
        assertFalse(GoModDirectiveEdits.requiresAreGrouped(lines("module m\nrequire a v1\nrequire b v2 // indirect\nrequire c v3 // indirect\n")))
    }

    @Test fun mergeRequiresSplitsByKind() {
        val before = "module m\n\ngo 1.22\n\nrequire a v1\n\nrequire (\n\t// for the tests\n\tb v2 // indirect\n\tc v3\n)\n\nrequire d v4 // indirect; old\n\nreplace x => ../x\n"
        val after = "module m\n\ngo 1.22\n\nrequire (\n\ta v1\n\tc v3\n)\n\nrequire (\n\t// for the tests\n\tb v2 // indirect\n\td v4 // indirect; old\n)\n\nreplace x => ../x\n"
        assertEquals(after, text(GoModDirectiveEdits.mergeRequires(lines(before))))
        assertTrue(GoModDirectiveEdits.requiresAreGrouped(lines(after)))
        assertNull(GoModDirectiveEdits.mergeRequires(lines("module m\nrequire a v1\n")))
    }

    @Test fun mergeRequiresOfOneKind() {
        assertEquals("module m\nrequire (\n\ta v1\n\tb v2\n)\n", text(GoModDirectiveEdits.mergeRequires(lines("module m\nrequire a v1\nrequire b v2\n"))))
    }

    @Test fun removeEntriesDropsAnEmptiedBlock() {
        assertEquals("module m\n\ngo 1.22\n", text(GoModDirectiveEdits.removeEntries(lines("module m\n\ngo 1.22\n\nreplace (\n\ta => ../a\n)\n"), setOf(5))))
        assertEquals("module m\nreplace (\n\tb => ../b\n)\n", text(GoModDirectiveEdits.removeEntries(lines("module m\nreplace (\n\ta => ../a\n\tb => ../b\n)\n"), setOf(2))))
        assertEquals("module m\n", text(GoModDirectiveEdits.removeEntries(lines("module m\nreplace a => ../a\n"), setOf(1))))
    }

    @Test fun workspaceReplacesAreLocalModules() {
        val text = "module m\n\ngo 1.22\n\nreplace a.com/a => ../a\nreplace (\n\tb.com/b v1.0.0 => ./b\n\tc.com/c => c.com/fork v1.2.0\n\td.com/d => ../nogomod\n)\n"
        val dirs = setOf("../a", "./b")
        assertEquals(listOf("../a", "./b"), GoModLayout.workspaceReplaces(text) { it in dirs }.map { it.newPath })
        val (work, mod) = GoModLayout.migrateToWorkspace(text) { it in dirs }!!
        assertEquals("go 1.22\n\nuse (\n\t.\n\t../a\n\t./b\n)\n", work)
        assertEquals("module m\n\ngo 1.22\n\nreplace (\n\tc.com/c => c.com/fork v1.2.0\n\td.com/d => ../nogomod\n)\n", text(mod))
        assertNull(GoModLayout.migrateToWorkspace("module m\nreplace a => b v1\n") { true })
    }

    @Test fun goWorkNamesAtLeastGo118() {
        assertEquals("go 1.18\n\nuse (\n\t.\n\t../a\n)\n", GoModLayout.goWork("1.16", listOf("..\\a\\")))
        assertEquals("go 1.18\n\nuse (\n\t.\n)\n", GoModLayout.goWork(null, emptyList()))
    }

    @Test fun ignorePathsFromRootAndAnywhere() {
        val text = "module m\n\ngo 1.25\n\nignore ./node_modules\nignore (\n\t./missing\n\tstatic\n\tnowhere\n\t\"./quoted dir\"\n)\n"
        assertEquals(listOf("./node_modules", "./missing", "static", "nowhere", "./quoted dir"), GoModLayout.ignorePaths(text).map { it.path })
        val root = setOf("./node_modules", "./quoted dir")
        val anywhere = mapOf("static" to true, "nowhere" to false)
        val unresolved = GoModLayout.unresolvedIgnores(text, { it in root }, { anywhere[it] })
        assertEquals(listOf("./missing" to 6, "nowhere" to 8), unresolved.map { it.path to it.line })
        // the module was too large to walk: not reported
        assertEquals(listOf("./missing"), GoModLayout.unresolvedIgnores(text, { it in root }, { null }).map { it.path })
    }

    @Test fun removeIgnore() {
        assertEquals("module m\nignore (\n\t./a\n)\n", text(GoModLayout.removeIgnore(lines("module m\nignore (\n\t./a\n\t./b\n)\n"), 3)))
        assertNull(GoModLayout.removeIgnore(lines("module m\n"), 5))
    }
}
