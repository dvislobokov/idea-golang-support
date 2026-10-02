package io.github.golangsupport.project.impl

import io.github.golangsupport.project.api.GoModuleVersion
import io.github.golangsupport.project.api.GoReplace
import io.github.golangsupport.project.api.GoRequire
import io.github.golangsupport.project.api.GoRetract
import junit.framework.TestCase
import java.nio.file.Path

class GoModFileParserTest : TestCase() {

    private val fullGoMod = """
        // Leading comment block (not attached: blank line follows).

        // Deprecated: use example.com/new instead.
        // Second paragraph line.
        module "example.com/old" // trailing comment

        go 1.23.0

        toolchain go1.27.1

        godebug default=go1.21

        godebug (
        	panicnil=1
        	asynctimerchan=0
        )

        require golang.org/x/text v0.14.0

        require (
        	// comment before an entry
        	github.com/Azure/azure-sdk v1.2.3
        	golang.org/x/sys v0.15.0 // indirect
        	golang.org/x/mod v0.17.0 // indirect; tagx:ignore
        	"example.com/quoted path" v1.0.0
        	`example.com/raw` v2.0.0+incompatible
        )

        exclude example.com/bad v1.0.0

        exclude (
        	example.com/bad v1.1.0
        )

        replace example.com/a => ../a

        replace (
        	example.com/b v1.0.0 => example.com/b-fork v1.0.1
        	example.com/c => example.com/c v1.5.0
        	example.com/d v0.1.0 => ./local/d // local
        )

        // Published accidentally.
        retract v1.0.0

        retract (
        	[v1.1.0, v1.2.9] // broken range
        	// Security issue.
        	v1.3.0
        )

        tool golang.org/x/tools/cmd/stringer

        tool (
        	example.com/tool/cmd/a
        	example.com/tool/cmd/b
        )

        ignore ./node_modules

        ignore (
        	./third_party/js
        	"./with space"
        )
    """.trimIndent()

    fun testEveryGoModDirective() {
        val mod = GoModFileParser.parseGoMod(fullGoMod)
        assertEquals(emptyList<String>(), mod.errors)
        assertEquals("example.com/old", mod.module)
        // As in modfile: the suffix comment is part of the directive comment, hence of the paragraph.
        assertEquals("use example.com/new instead.\nSecond paragraph line.\ntrailing comment", mod.deprecated)
        assertEquals("1.23.0", mod.go)
        assertEquals("go1.27.1", mod.toolchain)
        assertEquals(listOf("default" to "go1.21", "panicnil" to "1", "asynctimerchan" to "0"), mod.godebug)
        assertEquals(
            listOf(
                GoRequire("golang.org/x/text", "v0.14.0", false),
                GoRequire("github.com/Azure/azure-sdk", "v1.2.3", false),
                GoRequire("golang.org/x/sys", "v0.15.0", true),
                GoRequire("golang.org/x/mod", "v0.17.0", true),
                GoRequire("example.com/quoted path", "v1.0.0", false),
                GoRequire("example.com/raw", "v2.0.0+incompatible", false),
            ),
            mod.requires,
        )
        assertEquals(listOf(GoModuleVersion("example.com/bad", "v1.0.0"), GoModuleVersion("example.com/bad", "v1.1.0")), mod.excludes)
        assertEquals(
            listOf(
                GoReplace("example.com/a", null, "../a", null),
                GoReplace("example.com/b", "v1.0.0", "example.com/b-fork", "v1.0.1"),
                GoReplace("example.com/c", null, "example.com/c", "v1.5.0"),
                GoReplace("example.com/d", "v0.1.0", "./local/d", null),
            ),
            mod.replaces,
        )
        assertTrue(mod.replaces[0].isLocal)
        assertFalse(mod.replaces[1].isLocal)
        assertEquals(
            listOf(
                GoRetract("v1.0.0", "v1.0.0", "Published accidentally."),
                GoRetract("v1.1.0", "v1.2.9", "broken range"),
                GoRetract("v1.3.0", "v1.3.0", "Security issue."),
            ),
            mod.retracts,
        )
        assertEquals(listOf("golang.org/x/tools/cmd/stringer", "example.com/tool/cmd/a", "example.com/tool/cmd/b"), mod.tools)
        assertEquals(listOf("./node_modules", "./third_party/js", "./with space"), mod.ignores)
    }

    fun testRoundTrip() {
        val mod = GoModFileParser.parseGoMod(fullGoMod)
        val printed = GoModFileParser.format(mod)
        val reparsed = GoModFileParser.parseGoMod(printed)
        assertEquals(printed, mod, reparsed)
        assertEquals(printed, GoModFileParser.format(reparsed))
    }

    fun testErrorsAreReportedAndSkipped() {
        val mod = GoModFileParser.parseGoMod(
            """
            module example.com/m
            go
            require example.com/x
            replace a b c d e
            frobnicate x
            require (
            	example.com/ok v1.0.0
            """.trimIndent(),
        )
        assertEquals("example.com/m", mod.module)
        assertEquals(listOf(GoRequire("example.com/ok", "v1.0.0", false)), mod.requires)
        assertEquals(5, mod.errors.size)
        assertTrue(mod.errors.toString(), mod.errors.any { it.contains("unknown directive: frobnicate") })
        assertTrue(mod.errors.toString(), mod.errors.any { it.contains("unterminated block") })
    }

    fun testEmptyBlockAndQuotes() {
        val mod = GoModFileParser.parseGoMod("module m\nrequire ()\nrequire \"a\\x2fb\" v1.0.0\n")
        assertEquals(emptyList<String>(), mod.errors)
        assertEquals(listOf(GoRequire("a/b", "v1.0.0", false)), mod.requires)
        assertEquals("a\"b\\cé", GoModFileParser.unquote("\"a\\\"b\\\\c\\u00e9\""))
        assertEquals("raw\\n", GoModFileParser.unquote("`raw\\n`"))
        assertNull(GoModFileParser.unquote("\"bad\\q\""))
    }

    fun testGoWork() {
        val work = GoModFileParser.parseGoWork(
            """
            go 1.22

            toolchain go1.27.1

            godebug httpmuxgo121=1

            use ./a

            use (
            	./b
            	../shared // comment
            	"./with space"
            )

            replace example.com/x v1.0.0 => ./x
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), work.errors)
        assertEquals("1.22", work.go)
        assertEquals("go1.27.1", work.toolchain)
        assertEquals(listOf("httpmuxgo121" to "1"), work.godebug)
        assertEquals(listOf("./a", "./b", "../shared", "./with space"), work.uses)
        assertEquals(listOf(GoReplace("example.com/x", "v1.0.0", "./x", null)), work.replaces)
        assertEquals(1, GoModFileParser.parseGoWork("module x\n").errors.size)
    }

    fun testGoSum() {
        val entries = GoModFileParser.parseGoSum(
            """
            github.com/Abirdcfly/dupword v0.1.8/go.mod h1:XZrhVnI7YGpsTiWZANSQaBJ4QpL/Tq5vIEdKJJAs9WI=
            golang.org/x/sync v0.20.0 h1:abc=
            golang.org/x/sync v0.20.0/go.mod h1:def=

            malformed line
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                GoSumEntry("github.com/Abirdcfly/dupword", "v0.1.8", true, "h1:XZrhVnI7YGpsTiWZANSQaBJ4QpL/Tq5vIEdKJJAs9WI="),
                GoSumEntry("golang.org/x/sync", "v0.20.0", false, "h1:abc="),
                GoSumEntry("golang.org/x/sync", "v0.20.0", true, "h1:def="),
            ),
            entries,
        )
    }

    fun testVendorModulesTxt() {
        val modules = GoModFileParser.parseVendorModulesTxt(
            """
            # example.com/dep v1.2.0
            ## explicit; go 1.21
            example.com/dep
            example.com/dep/sub
            # example.com/indirect v0.3.0
            ## go 1.16
            example.com/indirect/pkg
            # example.com/replaced v1.0.0 => ../replaced
            ## explicit
            example.com/replaced
            # example.com/forked v1.0.0 => example.com/fork v1.1.0
            ## explicit; go 1.20
            example.com/forked/x
            # example.com/wild => ../wild
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                GoVendoredModule("example.com/dep", "v1.2.0", null, true, "1.21", listOf("example.com/dep", "example.com/dep/sub")),
                GoVendoredModule("example.com/indirect", "v0.3.0", null, false, "1.16", listOf("example.com/indirect/pkg")),
                GoVendoredModule("example.com/replaced", "v1.0.0", GoModuleVersion("../replaced", null), true, null, listOf("example.com/replaced")),
                GoVendoredModule("example.com/forked", "v1.0.0", GoModuleVersion("example.com/fork", "v1.1.0"), true, "1.20", listOf("example.com/forked/x")),
                GoVendoredModule("example.com/wild", null, GoModuleVersion("../wild", null), false, null, emptyList()),
            ),
            modules,
        )
    }

    fun testModuleCacheLayout() {
        assertEquals("github.com/!azure/azure-sdk", GoModuleCacheLayout.escape("github.com/Azure/azure-sdk"))
        assertEquals("github.com/Azure/azure-sdk", GoModuleCacheLayout.unescape("github.com/!azure/azure-sdk"))
        assertNull(GoModuleCacheLayout.unescape("github.com/Azure"))
        assertNull(GoModuleCacheLayout.unescape("bad!"))
        val layout = GoModuleCacheLayout(Path.of("/cache"))
        assertEquals(Path.of("/cache/cache/download/github.com/!burnt!sushi/toml/@v/v1.0.0.mod"), layout.modFile("github.com/BurntSushi/toml", "v1.0.0"))
        assertEquals(Path.of("/cache/cache/download/github.com/!burnt!sushi/toml/@v/list"), layout.listFile("github.com/BurntSushi/toml"))
        assertEquals(Path.of("/cache/github.com/!burnt!sushi/toml@v1.0.0-!r!c1"), layout.extractedDir("github.com/BurntSushi/toml", "v1.0.0-RC1"))
        assertEquals(
            GoModuleCacheLayout.Located("github.com/BurntSushi/toml", "v1.0.0", "internal/x"),
            layout.locate(Path.of("/cache/github.com/!burnt!sushi/toml@v1.0.0/internal/x")),
        )
        assertEquals(GoModuleCacheLayout.Located("golang.org/x/sync", "v0.20.0", ""), layout.locate(Path.of("/cache/golang.org/x/sync@v0.20.0")))
        assertNull(layout.locate(Path.of("/cache/cache/download/golang.org/x/sync/@v")))
        assertNull(layout.locate(Path.of("/elsewhere/x@v1.0.0")))
    }

    fun testSemVer() {
        val ordered = listOf(
            "bad", "v0.0.0-20190101000000-abcdefabcdef", "v0.0.1", "v0.1.0", "v1", "v1.0.1-alpha", "v1.0.1-alpha.1",
            "v1.0.1-alpha.beta", "v1.0.1-beta", "v1.0.1-beta.2", "v1.0.1-beta.11", "v1.0.1-rc.1", "v1.0.1",
            "v1.2.0-0.20240101000000-abcdefabcdef", "v1.2.0", "v2.0.0+incompatible", "v10.0.0",
        )
        for (i in ordered.indices) for (j in ordered.indices) {
            assertEquals("${ordered[i]} vs ${ordered[j]}", i.compareTo(j).coerceIn(-1, 1), SemVer.compare(ordered[i], ordered[j]).coerceIn(-1, 1))
        }
        assertEquals(0, SemVer.compare("v1", "v1.0.0"))
        assertEquals(0, SemVer.compare("v1.0.0+meta", "v1.0.0"))
        assertTrue(SemVer.isPseudo("v0.0.0-20190101000000-abcdefabcdef"))
        assertTrue(SemVer.isPseudo("v1.2.0-0.20240101000000-abcdefabcdef"))
        assertTrue(SemVer.isPseudo("v1.2.4-pre.0.20240101000000-abcdefabcdef"))
        assertFalse(SemVer.isPseudo("v1.2.0"))
        assertEquals("v2", SemVer.major("v2.3.4"))
    }

    fun testMiniJson() {
        val values = MiniJson.parseStream("{\"Path\":\"a\",\"Main\":true,\"N\":1.5,\"L\":[1,null,\"x\\u0041\\n\"],\"O\":{}}\n{\"Path\":\"b\"}")
        assertEquals(2, values.size)
        val first = values[0] as Map<*, *>
        assertEquals("a", first["Path"])
        assertEquals(true, first["Main"])
        assertEquals(1.5, first["N"])
        assertEquals(listOf(1.0, null, "xA\n"), first["L"])
        assertEquals(emptyMap<String, Any?>(), first["O"])
        try {
            MiniJson.parse("{\"a\":}")
            fail()
        } catch (_: MiniJson.JsonException) {
        }
    }
}
