package io.github.golangsupport

import io.github.golangsupport.settings.GoExperiments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoExperimentsTest {
    private val flags = "package goexperiment\n\ntype Flags struct {\n\tFieldTrack        bool\n\tRegabiArgs bool\n\tArenas bool\n\tGreenTeaGC bool\n}\n"
    private val exp = "func ParseGOEXPERIMENT() {\n\tbaseline := goexperiment.Flags{\n\t\tRegabiArgs: regabiSupported,\n\t\tGreenTeaGC: true,\n\t}\n}\n"
    private val experiments = GoExperiments.parse(flags, exp)

    @Test fun namesAndDefaultsComeFromTheToolchainSources() {
        assertEquals(listOf("fieldtrack", "regabiargs", "arenas", "greenteagc"), experiments.map { it.name })
        assertEquals(listOf(false, true, false, true), experiments.map { it.defaultOn })
        assertTrue(experiments.single { it.name == "regabiargs" }.platformDependent)
    }

    @Test fun onlyDifferencesFromTheBaselineAreWritten() {
        assertEquals("arenas,nogreenteagc", GoExperiments.compose(experiments, setOf("regabiargs", "arenas")))
        assertEquals("", GoExperiments.compose(experiments, setOf("regabiargs", "greenteagc")))
        assertEquals("arenas,myown", GoExperiments.compose(experiments, setOf("regabiargs", "greenteagc", "arenas"), previous = "arenas,myown"))
    }

    @Test fun theTextIsReadBackAsState() {
        val state = GoExperiments.state(experiments, "arenas,nogreenteagc")
        assertEquals(true, state["arenas"]); assertEquals(false, state["greenteagc"]); assertEquals(true, state["regabiargs"])
        assertEquals(false, GoExperiments.state(experiments, "none")["regabiargs"])
    }

    @Test fun theInstalledToolchainHasExperiments() {
        val goroot = java.nio.file.Path.of(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:/Program Files/Go")
        if (!java.nio.file.Files.isDirectory(goroot)) return
        val real = GoExperiments.of(goroot)
        assertTrue(real.toString(), real.any { it.name == "arenas" } && real.any { it.defaultOn })
    }
}
