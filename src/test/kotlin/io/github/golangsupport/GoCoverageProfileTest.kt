package io.github.golangsupport

import com.intellij.rt.coverage.data.LineCoverage
import io.github.golangsupport.coverage.GoCoverageProjectData
import io.github.golangsupport.coverage.GoCoverageRules
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.testing.GoCoverage
import io.github.golangsupport.testing.GoCoverageFiles
import io.github.golangsupport.testing.GoLineCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A cover profile of `go test -covermode=atomic` as line statuses for the coverage engine of the platform. */
class GoCoverageProfileTest {
    private val profile = """
        mode: atomic
        example.com/app/store/order.go:10.30,12.2 2 5
        example.com/app/store/order.go:14.20,15.10 1 3
        example.com/app/store/order.go:15.10,17.3 1 0
        example.com/app/store/order.go:20.2,21.15 1 0
        example.com/app/cmd/main.go:5.13,7.2 2 1
        example.com/other/lib.go:3.1,4.2 1 1
    """.trimIndent()

    private val modules = listOf("example.com/app" to "C:/work/app", "example.com/app/cmd" to "C:/work/cmd-module")

    @Test fun lineStatusesAndHits() {
        val data = GoCoverage.parse(profile)
        assertEquals("atomic", data.mode)
        val lines = data.lineHits("example.com/app/store/order.go")
        assertEquals(GoLineCoverage.COVERED to 5, lines[10])
        assertEquals(GoLineCoverage.COVERED to 5, lines[12])
        assertEquals(GoLineCoverage.COVERED to 3, lines[14])
        // line 15 ends a block that ran and starts one that did not
        assertEquals(GoLineCoverage.PARTIAL to 3, lines[15])
        assertEquals(GoLineCoverage.UNCOVERED to 0, lines[16])
        assertEquals(GoLineCoverage.UNCOVERED to 0, lines[21])
        assertNull(lines[13])
    }

    @Test fun filesResolveByTheLongestModulePath() {
        assertEquals("C:/work/app/store/order.go", GoCoverageFiles.resolve("example.com/app/store/order.go", modules))
        assertEquals("C:/work/cmd-module/main.go", GoCoverageFiles.resolve("example.com/app/cmd/main.go", modules))
        assertNull(GoCoverageFiles.resolve("example.com/other/lib.go", modules))
        // a module path is not a prefix of a longer name: example.com/application is another module
        assertNull(GoCoverageFiles.resolve("example.com/application/x.go", modules))
        assertEquals("/home/dev/scratch/main.go", GoCoverageFiles.resolve("/home/dev/scratch/main.go", emptyList()))
        assertEquals("C:/dev/scratch/main.go", GoCoverageFiles.resolve("C:\\dev\\scratch\\main.go", emptyList()))
    }

    @Test fun projectDataForThePlatform() {
        val data = GoCoverageProjectData.build(GoCoverage.parse(profile), modules)
        assertEquals(setOf("C:/work/app/store/order.go", "C:/work/cmd-module/main.go"), data.classes.keys)
        val order = data.getClassData("C:/work/app/store/order.go")
        assertEquals(LineCoverage.FULL.toInt(), order.getLineData(10).status)
        assertEquals(5, order.getLineData(10).hits)
        assertEquals(LineCoverage.PARTIAL.toInt(), order.getLineData(15).status)
        assertEquals(LineCoverage.NONE.toInt(), order.getLineData(21).status)
        assertNull(order.getLineData(13))
    }

    @Test fun rules() {
        assertTrue(GoCoverageRules.applicable(GoCommand.TEST, overSsh = false))
        assertFalse(GoCoverageRules.applicable(GoCommand.RUN, overSsh = false))
        assertFalse(GoCoverageRules.applicable(GoCommand.TEST, overSsh = true))
        assertTrue(GoCoverageRules.counted("order.go"))
        assertFalse(GoCoverageRules.counted("order_test.go"))
        assertFalse(GoCoverageRules.counted("go.mod"))
        assertEquals("Go tests", GoCoverageRules.suiteName(""))
    }
}
