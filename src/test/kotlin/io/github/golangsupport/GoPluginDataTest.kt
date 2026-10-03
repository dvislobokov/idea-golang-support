package io.github.golangsupport

import io.github.golangsupport.cli.GoPluginData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Moving the plugin data to a directory where programs may run (seen at a user's company: only under /home/work/<user>). */
class GoPluginDataTest {
    @Test fun moveCarriesThePartsAndLeavesTheRest() {
        val from = Files.createTempDirectory("go-data-from")
        val to = Files.createTempDirectory("go-data-to")
        Files.createDirectories(from.resolve("delve/abc"))
        Files.writeString(from.resolve("delve/abc/dlv"), "binary")
        Files.createDirectories(from.resolve("catalogue/v3"))
        Files.writeString(from.resolve("catalogue/v3/std.json"), "{}")
        Files.writeString(from.resolve("unrelated.txt"), "stays")
        assertEquals(emptyList<String>(), GoPluginData.move(from, to))
        assertEquals("binary", Files.readString(to.resolve("delve/abc/dlv")))
        assertEquals("{}", Files.readString(to.resolve("catalogue/v3/std.json")))
        assertFalse(Files.exists(from.resolve("delve")))
        assertTrue("only the plugin's parts move", Files.exists(from.resolve("unrelated.txt")))
        assertFalse(Files.exists(to.resolve("unrelated.txt")))
    }

    @Test fun moveToTheSameDirectoryDoesNothing() {
        val dir = Files.createTempDirectory("go-data-same")
        Files.createDirectories(dir.resolve("bin"))
        assertEquals(emptyList<String>(), GoPluginData.move(dir, dir))
        assertTrue(Files.isDirectory(dir.resolve("bin")))
    }
}
