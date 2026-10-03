package io.github.golangsupport.project.impl

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** `go list -m -json all` across sessions: kept per go.mod location, valid only under its stamp key; the model is bumped only for a different graph. */
class GoListDiskCacheTest : TestCase() {
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("go-list-cache")
        GoListDiskCache.setDirectoryForTests(dir)
    }

    override fun tearDown() {
        try {
            GoListDiskCache.setDirectoryForTests(null)
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private val output = """
        {"Path":"example.com/app","Main":true,"Dir":"/w/app","GoVersion":"1.22"}
        {"Path":"golang.org/x/text","Version":"v0.14.0","Dir":"/m/text"}
    """.trimIndent()

    fun testTheOutputIsServedUnderItsKeyOnly() {
        val location = GoModuleGraphBuilder.Location(Path.of("/w/app/go.mod"), null)
        assertNull(GoListDiskCache.read(location, "k1"))
        GoListDiskCache.write(location, "k1", output)
        assertEquals(output, GoListDiskCache.read(location, "k1"))
        assertNull("go.sum changed: stale", GoListDiskCache.read(location, "k2"))
        GoListDiskCache.write(location, "k2", "{}")
        assertEquals("{}", GoListDiskCache.read(location, "k2"))
        assertNull(GoListDiskCache.read(location, "k1"))
    }

    fun testEachLocationHasItsFile() {
        val a = GoModuleGraphBuilder.Location(Path.of("/w/a/go.mod"), null)
        val b = GoModuleGraphBuilder.Location(Path.of("/w/b/go.mod"), null)
        val work = GoModuleGraphBuilder.Location(Path.of("/w/a/go.mod"), Path.of("/w/go.work"))
        assertEquals(3, setOf(GoListDiskCache.fileOf(a), GoListDiskCache.fileOf(b), GoListDiskCache.fileOf(work)).size)
        assertEquals(dir, GoListDiskCache.fileOf(a).parent)
    }

    fun testDecodeRejectsAnotherFormat() {
        assertEquals("x\ny", GoListDiskCache.decode(GoListDiskCache.encode("key", "x\ny"), "key"))
        assertNull(GoListDiskCache.decode("key\n$output", "key"))
        assertNull(GoListDiskCache.decode("", "key"))
    }

    fun testTheModelIsBumpedOnlyForOtherModules() {
        val graph = GoListModuleGraph.parse(output)
        assertFalse(DefaultGoModuleGraphProvider.differs(graph, GoListModuleGraph.parse(output)))
        val more = GoListModuleGraph.parse(output + "\n" + """{"Path":"golang.org/x/sys","Version":"v0.20.0"}""")
        assertTrue(DefaultGoModuleGraphProvider.differs(more, graph))
        val other = GoListModuleGraph.parse(output.replace("v0.14.0", "v0.15.0"))
        assertTrue(DefaultGoModuleGraphProvider.differs(other, graph))
    }
}
