package io.github.golangsupport

import io.github.golangsupport.cli.GoModulesEnvironment
import io.github.golangsupport.mod.GoModDownloadChoice
import io.github.golangsupport.settings.GoVendoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Settings | Go | Go Modules: the Environment field, the vendoring flag through GOFLAGS, the four choices of the download. */
class GoModulesEnvironmentTest {
    @Test fun theEnvironmentFieldIsNameValuePairs() {
        assertEquals(mapOf("GOPROXY" to "https://proxy.example,direct", "GOPRIVATE" to "example.com/*"),
            GoModulesEnvironment.parse(" GOPROXY=https://proxy.example,direct ; GOPRIVATE=example.com/*;"))
        assertEquals(mapOf("GOFLAGS" to "-mod=mod -trimpath", "GONOSUMDB" to ""), GoModulesEnvironment.parse("GOFLAGS=-mod=mod -trimpath\nGONOSUMDB="))
        assertEquals(emptyMap<String, String>(), GoModulesEnvironment.parse(""))
        // what is not NAME=value is skipped, and named by the validation
        assertEquals(mapOf("A" to "1"), GoModulesEnvironment.parse("A=1;oops;=2;1X=3"))
        assertEquals("oops", GoModulesEnvironment.invalidEntry("A=1;oops"))
        assertEquals("1X=3", GoModulesEnvironment.invalidEntry("1X=3"))
        assertNull(GoModulesEnvironment.invalidEntry("GOPROXY=off; GOFLAGS=-mod=vendor"))
        assertNull(GoModulesEnvironment.invalidEntry(""))
    }

    @Test fun theModFlagJoinsGoflagsUnlessOneIsThere() {
        assertEquals("-mod=vendor", GoModulesEnvironment.goflags(null, "-mod=vendor"))
        assertEquals("-trimpath -mod=mod", GoModulesEnvironment.goflags(" -trimpath ", "-mod=mod"))
        // the user's own -mod wins, in either spelling
        assertNull(GoModulesEnvironment.goflags("-mod=readonly", "-mod=vendor"))
        assertNull(GoModulesEnvironment.goflags("-trimpath --mod=mod", "-mod=vendor"))
        assertNull(GoModulesEnvironment.goflags("-trimpath", null))
    }

    @Test fun vendoringGivesAFlagOnlyToAVendoredModule() {
        assertNull(GoVendoring.AUTO.modFlag(true))
        assertEquals("-mod=vendor", GoVendoring.ALWAYS.modFlag(true))
        assertEquals("-mod=mod", GoVendoring.NEVER.modFlag(true))
        assertNull(GoVendoring.ALWAYS.modFlag(false))
        assertNull(GoVendoring.NEVER.modFlag(false))
    }

    @Test fun theVendorDirectoryIsTheOneOfTheNearestModule() {
        val root = Files.createTempDirectory("go-modules-env").toFile()
        try {
            val module = root.resolve("app").apply { mkdirs() }
            module.resolve("go.mod").writeText("module example.com/app\n\ngo 1.22\n")
            val pkg = module.resolve("internal/store").apply { mkdirs() }
            assertFalse(GoModulesEnvironment.hasVendor(pkg))
            module.resolve("vendor").mkdirs()
            module.resolve("vendor/modules.txt").writeText("# example.com/dep v1.0.0\n")
            assertTrue(GoModulesEnvironment.hasVendor(pkg))
            assertTrue(GoModulesEnvironment.hasVendor(module))
            // a nested module without vendor stops the search
            val nested = module.resolve("tools").apply { mkdirs() }
            nested.resolve("go.mod").writeText("module example.com/app/tools\n")
            assertFalse(GoModulesEnvironment.hasVendor(nested))
            assertFalse(GoModulesEnvironment.hasVendor(null))

            assertEquals(mapOf("GOFLAGS" to "-mod=vendor"), GoModulesEnvironment.of("", GoVendoring.ALWAYS, pkg.path, null))
            assertEquals(mapOf("GOFLAGS" to "-trimpath -mod=mod", "GOPROXY" to "off"), GoModulesEnvironment.of("GOPROXY=off", GoVendoring.NEVER, pkg.path, "-trimpath"))
            // the field's GOFLAGS is the base, not the one of the IDE
            assertEquals("-race -mod=vendor", GoModulesEnvironment.of("GOFLAGS=-race", GoVendoring.ALWAYS, pkg.path, "-trimpath")["GOFLAGS"])
            // Automatically and an unvendored module: nothing is added, GOFLAGS of the IDE stays as it is
            assertEquals(emptyMap<String, String>(), GoModulesEnvironment.of("", GoVendoring.AUTO, pkg.path, "-trimpath"))
            assertEquals(emptyMap<String, String>(), GoModulesEnvironment.of("", GoVendoring.ALWAYS, nested.path, null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun theFourChoicesOfTheDownloadAreASwitchAndAnException() {
        assertEquals(listOf(true, false, true, false), GoModDownloadChoice.entries.map { it.enabledHere })
        for (choice in GoModDownloadChoice.entries) assertEquals(choice, GoModDownloadChoice.of(choice.global, choice.exception))
        assertEquals(GoModDownloadChoice.ONLY_THIS, GoModDownloadChoice.of(global = false, exception = true))
        assertEquals("Enable for all projects", GoModDownloadChoice.ALL.toString())
    }
}
