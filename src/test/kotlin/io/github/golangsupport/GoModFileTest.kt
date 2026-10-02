package io.github.golangsupport

import io.github.golangsupport.mod.GoModFile
import io.github.golangsupport.mod.GoReplace
import io.github.golangsupport.mod.GoRequire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the plugin reads from go.mod / go.work through the native parser: the grammar is one, the lines are kept. */
class GoModFileTest {
    @Test fun quotedPathsAndComments() {
        val mod = GoModFile.parse(
            """
            // Deprecated: moved.
            module "example.com/quoted path" // trailing

            go 1.24

            require (
            	// a comment line

            	github.com/a/b v1.0.0 // indirect; tagx:ignore
            	`example.com/raw` v2.0.0+incompatible
            	"example.com/esc\x2fped" v0.1.0 // indirectly related, not indirect
            )
            """.trimIndent(),
        )
        assertEquals("example.com/quoted path", mod.modulePath)
        assertEquals("1.24", mod.goVersion)
        assertNull(mod.toolchain)
        assertEquals(
            listOf(GoRequire("github.com/a/b", "v1.0.0", true, 8), GoRequire("example.com/raw", "v2.0.0+incompatible", false, 9), GoRequire("example.com/esc/ped", "v0.1.0", false, 10)),
            mod.requires,
        )
    }

    @Test fun replaceExcludeTool() {
        val mod = GoModFile.parse(
            """
            module m
            replace example.com/a => ../a
            replace (
            	example.com/b v1.0.0 => example.com/b-fork v1.0.1
            	example.com/c => example.com/c v1.5.0
            	"example.com/d" v0.1.0 => "./local d" // local
            )
            exclude example.com/bad v1.0.0
            exclude (
            	example.com/bad v1.1.0
            )
            tool golang.org/x/tools/cmd/stringer
            tool (
            	example.com/tool/cmd/a
            )
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                GoReplace("example.com/a", null, "../a", null, 1),
                GoReplace("example.com/b", "v1.0.0", "example.com/b-fork", "v1.0.1", 3),
                GoReplace("example.com/c", null, "example.com/c", "v1.5.0", 4),
                GoReplace("example.com/d", "v0.1.0", "./local d", null, 5),
            ),
            mod.replaces,
        )
        assertTrue(mod.replaces[0].isLocal)
        assertFalse(mod.replaces[1].isLocal)
        assertEquals(listOf("example.com/bad" to "v1.0.0", "example.com/bad" to "v1.1.0"), mod.excludes)
        assertEquals(listOf("golang.org/x/tools/cmd/stringer", "example.com/tool/cmd/a"), mod.tools)
        assertEquals(mod.replaces[1], mod.replacementOf(GoRequire("example.com/b", "v1.0.0", false, 0)))
        assertEquals(mod.replaces[2], mod.replacementOf(GoRequire("example.com/c", "v9.0.0", false, 0)))
        assertNull(mod.replacementOf(GoRequire("example.com/b", "v2.0.0", false, 0)))
    }

    @Test fun goWork() {
        val work = GoModFile.parse(
            """
            go 1.24
            toolchain go1.24.7

            use ./a
            use (
            	./b
            	../shared // comment
            	"./with space"
            )

            replace example.com/x v1.0.0 => ./x
            """.trimIndent(),
        )
        assertEquals("go1.24.7", work.toolchain)
        assertEquals(listOf("./a", "./b", "../shared", "./with space"), work.uses)
        assertEquals(listOf(GoReplace("example.com/x", "v1.0.0", "./x", null, 10)), work.replaces)
        assertNull(work.modulePath)
    }

    @Test fun linesInsideAndOutsideBlocks() {
        val mod = GoModFile.parse("module m\n\nrequire a v1.0.0\n\nrequire (\n\tb v1.0.0\n\n\tc v1.0.0 // indirect\n)\nrequire d v1.0.0\n")
        assertEquals(listOf("a" to 2, "b" to 5, "c" to 7, "d" to 9), mod.requires.map { it.path to it.line })
        assertEquals(listOf("c"), mod.indirectRequires.map { it.path })
    }

    @Test fun malformedDirectivesAreSkipped() {
        val mod = GoModFile.parse("module m\ngo\nrequire a\nreplace a b c d e\nfrobnicate x\nrequire ()\nrequire ok v1.0.0 // ok\n")
        assertEquals("m", mod.modulePath)
        assertNull(mod.goVersion)
        assertEquals(listOf(GoRequire("ok", "v1.0.0", false, 6)), mod.requires)
        assertTrue(mod.replaces.isEmpty())
    }
}
