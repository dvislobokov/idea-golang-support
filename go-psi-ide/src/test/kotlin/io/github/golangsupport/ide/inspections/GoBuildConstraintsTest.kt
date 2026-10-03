package io.github.golangsupport.ide.inspections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure build-constraint functions behind `GoBuildConstraintInspection`. */
class GoBuildConstraintsTest {

    private fun error(line: String) = GoBuildConstraints.syntaxError(line)

    @Test
    fun validExpressions() {
        assertNull(error("//go:build linux"))
        assertNull(error("//go:build linux && (amd64 || arm64) && !cgo"))
        assertNull(error("//go:build  go1.21 || integration.v2"))
    }

    @Test
    fun syntaxErrorsWithOffsets() {
        assertEquals(15, error("//go:build a &&")!!.offset)
        assertEquals("unexpected end of expression", error("//go:build a &&")!!.message)
        assertEquals("missing close paren", error("//go:build (a || b")!!.message)
        assertEquals(13, error("//go:build a & b")!!.offset)
        assertEquals("double negation not allowed", error("//go:build !!a")!!.message)
        assertEquals("unexpected token )", error("//go:build a)")!!.message)
        assertEquals(14, error("//go:build  a b")!!.offset)
    }

    @Test
    fun plusBuildConversion() {
        assertEquals("linux", GoBuildConstraints.plusBuildToGoBuild(listOf("// +build linux")))
        assertEquals("linux || darwin", GoBuildConstraints.plusBuildToGoBuild(listOf("// +build linux darwin")))
        assertEquals("linux && amd64", GoBuildConstraints.plusBuildToGoBuild(listOf("// +build linux,amd64")))
        assertEquals("(linux && !cgo) || darwin", GoBuildConstraints.plusBuildToGoBuild(listOf("// +build linux,!cgo darwin")))
        assertEquals("(linux || darwin) && amd64", GoBuildConstraints.plusBuildToGoBuild(listOf("// +build linux darwin", "// +build amd64")))
        assertEquals("ignore", GoBuildConstraints.plusBuildToGoBuild(listOf("// +build !!linux"))) // as gofmt: an invalid term becomes "ignore"
        assertNull(GoBuildConstraints.plusBuildToGoBuild(emptyList()))
    }

    @Test
    fun suggestions() {
        assertEquals("linux", GoBuildConstraints.suggest("linx"))
        assertEquals("windows", GoBuildConstraints.suggest("windowz"))
        assertEquals("amd64", GoBuildConstraints.suggest("amd644"))
        assertEquals("darwin", GoBuildConstraints.suggest("darwinn"))
        assertNull(GoBuildConstraints.suggest("linux"))
        assertNull(GoBuildConstraints.suggest("integration"))
        assertNull(GoBuildConstraints.suggest("cgo"))
        assertNull(GoBuildConstraints.suggest("go1.21"))
        assertNull(GoBuildConstraints.suggest("js"))
    }

    @Test
    fun editDistance() {
        assertTrue(GoBuildConstraints.withinOneEdit("linx", "linux"))
        assertTrue(GoBuildConstraints.withinOneEdit("linuxx", "linux"))
        assertTrue(GoBuildConstraints.withinOneEdit("lunux", "linux"))
        assertFalse(GoBuildConstraints.withinOneEdit("linux", "linux"))
        assertFalse(GoBuildConstraints.withinOneEdit("lnx", "linux"))
    }

    @Test
    fun tagOffsets() {
        val tags = GoBuildConstraints.tags("//go:build linx && !amd64", GoBuildConstraints.GO_BUILD.length)
        assertEquals(listOf(GoBuildConstraints.TagAt("linx", 11), GoBuildConstraints.TagAt("amd64", 20)), tags)
    }
}
