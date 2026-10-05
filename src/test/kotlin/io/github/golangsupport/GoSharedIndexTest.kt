package io.github.golangsupport

import io.github.golangsupport.sharedindex.GoSharedIndexCommand
import io.github.golangsupport.sharedindex.GoSharedIndexKey
import io.github.golangsupport.sharedindex.GoSharedIndexLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class GoSharedIndexTest {
    private val version = "go1.27.1\ntime 2026-09-01T17:00:00Z\n"

    @Test
    fun keyOfARelease() {
        val key = GoSharedIndexKey.of(version, listOf("windows_amd64"), "linux", "arm64")!!
        assertEquals("go1.27.1", key.goVersion)
        assertEquals("windows" to "amd64", key.goos to key.goarch)
        assertEquals(12, key.hash.length)
        assertTrue(key.id, key.id.startsWith("go1.27.1-windows-amd64-"))
        assertEquals("1.27", key.goDirective)
    }

    @Test
    fun lineEndingsDoNotChangeTheHash() {
        assertEquals(GoSharedIndexKey.hashOf(version), GoSharedIndexKey.hashOf(version.replace("\n", "\r\n")))
        assertNotEquals(GoSharedIndexKey.hashOf(version), GoSharedIndexKey.hashOf(version.replace("17:00", "18:00")))
    }

    @Test
    fun developmentTreesAndUnknownHostsHaveNoKey() {
        assertNull(GoSharedIndexKey.of("devel go1.28-abcdef Mon Sep 1\n", listOf("linux_amd64"), "linux", "amd64"))
        assertNull(GoSharedIndexKey.of("", listOf("linux_amd64"), "linux", "amd64"))
        assertNull(GoSharedIndexKey.of(version, emptyList(), null, null))
        assertEquals("go1.28rc1", GoSharedIndexKey.versionOf("go1.28rc1\n"))
        assertEquals("go1.22", GoSharedIndexKey.versionOf("go1.22"))
    }

    @Test
    fun hostPrefersTheToolchainPairAmongCrossCompiledTools() {
        assertEquals("linux" to "arm64", GoSharedIndexKey.hostOf(listOf("linux_amd64", "linux_arm64", "race"), "linux", "arm64"))
        assertEquals("darwin" to "arm64", GoSharedIndexKey.hostOf(listOf("linux_amd64", "darwin_arm64"), "windows", "amd64"))
        assertEquals("plan9" to "386", GoSharedIndexKey.of(version, emptyList(), "plan9", "386")!!.let { it.goos to it.goarch })
    }

    @Test
    fun chunksAreTheIjxFilesInNameOrder() {
        assertEquals(listOf("a.ijx", "b.ijx"), GoSharedIndexLayout.chunks(listOf("b.ijx", "go-shared-index.json", "a.ijx", "a.ijx.sha256", "x.ijx.xz")))
    }

    @Test
    fun manifestRoundTrip() {
        val manifest = GoSharedIndexLayout.Manifest("go1.27.1-linux-amd64-0123", "go1.27.1", "IU-261.26222.65", "0.2.181", "2026-10-05T12:00")
        assertEquals(manifest, GoSharedIndexLayout.parseManifest(GoSharedIndexLayout.manifestJson(manifest)))
        assertNull(GoSharedIndexLayout.parseManifest("{"))
        assertNull(GoSharedIndexLayout.parseManifest("{}"))
    }

    @Test
    fun remoteIndexAddress() {
        assertEquals("https://cdn.example/go/index.json", GoSharedIndexLayout.remoteIndexUrl(" https://cdn.example/go/ "))
        assertEquals("https://cdn.example/go/list.json", GoSharedIndexLayout.remoteIndexUrl("https://cdn.example/go/list.json"))
        assertNull(GoSharedIndexLayout.remoteIndexUrl("  "))
    }

    @Test
    fun remoteChunkIsSelectedByKeyAndBuild() {
        val json = """
            {"chunks": [
              {"key": "go1.27.1-linux-amd64-aaa", "ideBuild": "IU-261.1", "url": "old.ijx"},
              {"key": "go1.27.1-linux-amd64-aaa", "ideBuild": "IU-261.26222.65", "url": "linux/261.26222.65.ijx", "sha256": "ff"},
              {"key": "go1.26.0-linux-amd64-bbb", "ideBuild": "PY-261.26222.65", "url": "https://other.example/x.ijx"},
              {"key": "broken"},
              42
            ]}
        """.trimIndent()
        val chunks = GoSharedIndexLayout.parseRemoteIndex(json)
        assertEquals(3, chunks.size)
        assertEquals("ff", GoSharedIndexLayout.select(chunks, "go1.27.1-linux-amd64-aaa", "IU-261.26222.65")?.sha256)
        // another product of the same build reads the same index format
        assertEquals("https://other.example/x.ijx", GoSharedIndexLayout.select(chunks, "go1.26.0-linux-amd64-bbb", "IU-261.26222.65")?.url)
        assertNull(GoSharedIndexLayout.select(chunks, "go1.26.0-linux-amd64-bbb", "IU-262.1"))
        assertEquals(emptyList<Any>(), GoSharedIndexLayout.parseRemoteIndex("not json"))
    }

    @Test
    fun relativeChunkAddressesResolveAgainstTheIndex() {
        assertEquals("https://cdn.example/go/linux/a.ijx", GoSharedIndexLayout.resolve("https://cdn.example/go/index.json", "linux/a.ijx"))
        assertEquals("https://cdn.example/go/a.ijx", GoSharedIndexLayout.resolve("https://cdn.example/go/index.json", "/a.ijx"))
        assertEquals("file:///srv/a.ijx", GoSharedIndexLayout.resolve("https://cdn.example/go/index.json", "file:///srv/a.ijx"))
        assertEquals("a.ijx", GoSharedIndexLayout.fileName("https://cdn.example/go/a.ijx?token=1"))
        assertEquals("chunk.ijx", GoSharedIndexLayout.fileName("https://cdn.example/go/chunk"))
        // the index is a remote document: no separators or drive letters may reach the file system
        assertEquals("x.ijx", GoSharedIndexLayout.fileName("https://cdn.example/go/..\\..\\x.ijx"))
        assertEquals("c_evil.ijx", GoSharedIndexLayout.fileName("https://cdn.example/go/c:evil"))
        assertEquals("chunk.ijx", GoSharedIndexLayout.fileName("https://cdn.example/go/.."))
    }

    @Test
    fun launchersPerOs() {
        val home = Path.of("ide")
        assertEquals(listOf(home.resolve("bin").resolve("idea.bat")), GoSharedIndexCommand.launcherCandidates(home, "idea", GoSharedIndexCommand.Os.WINDOWS))
        assertEquals(home.resolve("MacOS").resolve("pycharm"), GoSharedIndexCommand.launcherCandidates(home, "pycharm", GoSharedIndexCommand.Os.MAC).first())
        assertEquals(listOf(home.resolve("bin").resolve("webstorm.sh"), home.resolve("bin").resolve("webstorm")),
            GoSharedIndexCommand.launcherCandidates(home, "webstorm", GoSharedIndexCommand.Os.LINUX))
    }

    @Test
    fun dumpArguments() {
        val key = GoSharedIndexKey("go1.27.1", "linux", "amd64", "0123456789ab")
        val args = GoSharedIndexCommand.arguments(Path.of("w", "project"), Path.of("w", "out"), Path.of("w", "tmp"), key)
        assertEquals(listOf("dump-shared-index", "project"), args.take(2))
        assertTrue(args.toString(), "--project-dir=${Path.of("w", "project")}" in args)
        assertTrue(args.toString(), "--output=${Path.of("w", "out")}" in args)
        assertTrue(args.toString(), "--project-id=go-stdlib-go1.27.1-linux-amd64-0123456789ab" in args)
        assertTrue(args.toString(), "--compression=plain" in args)
        assertEquals("module gosharedindex\n\ngo 1.27\n", GoSharedIndexCommand.goMod(key))
    }

    @Test
    fun headlessRunGetsItsOwnStateButTheSamePlugins() {
        val text = GoSharedIndexCommand.properties(Path.of("C:\\work"), Path.of("C:\\Users\\me\\plugins"))
        if (java.io.File.separatorChar == '\\') {
            assertTrue(text, "idea.config.path=C:/work/config" in text)
            assertTrue(text, "idea.plugins.path=C:/Users/me/plugins" in text)
        }
        assertTrue(text, "idea.system.path=" in text && "idea.log.path=" in text)
        val vars = GoSharedIndexCommand.propertiesVariables("idea")
        assertEquals("IDEA_PROPERTIES", vars.first())
        assertTrue(vars.toString(), "WEBIDE_PROPERTIES" in vars && vars.size == vars.distinct().size)
    }

    @Test
    fun settingsCarryTheGoExecutable() {
        assertNull(GoSharedIndexCommand.settingsXml(" "))
        val xml = GoSharedIndexCommand.settingsXml("C:\\Go & co\\bin\\go.exe")!!
        assertTrue(xml, "<component name=\"GoSupportSettings\">" in xml)
        assertTrue(xml, "value=\"C:\\Go &amp; co\\bin\\go.exe\"" in xml)
    }
}
