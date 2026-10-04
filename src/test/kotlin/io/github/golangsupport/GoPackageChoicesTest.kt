package io.github.golangsupport

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoPackageChoices
import io.github.golangsupport.run.GoSshFilesTable

/** The Package list of a Go configuration: programs for go run, tested packages for go test, the stored target kept. */
class GoPackageChoicesTest : BasePlatformTestCase() {
    fun testProgramsAndTestedPackagesOfTheModule() {
        myFixture.addFileToProject("pc/go.mod", "module example.com/pc\n\ngo 1.22\n")
        myFixture.addFileToProject("pc/cmd/api/main.go", "package main\n\nfunc main() {}\n")
        myFixture.addFileToProject("pc/cmd/tool/tool.go", "package main\n\nfunc helper() {}\n")
        myFixture.addFileToProject("pc/store/store.go", "package store\n")
        myFixture.addFileToProject("pc/store/store_test.go", "package store\n")
        myFixture.addFileToProject("pc/store/testdata/x.go", "package x\n")
        myFixture.addFileToProject("pc/vendor/v/v.go", "package main\n\nfunc main() {}\n")
        val choices = ReadAction.compute<List<GoPackageChoices.Choice>, Throwable> { GoPackageChoices.collect(project) }
            .filter { it.label.startsWith("example.com/pc") }
        assertEquals(listOf("example.com/pc/cmd/api", "example.com/pc/cmd/tool", "example.com/pc/store"), choices.map { it.label })
        assertEquals(listOf("example.com/pc/cmd/api"), GoPackageChoices.forCommand(choices, GoCommand.RUN, null).map { it.label })
        assertEquals(listOf("example.com/pc/store"), GoPackageChoices.forCommand(choices, GoCommand.TEST, null).map { it.label })
        // a stored target stays in the list, first, even when it does not fit the kind
        val api = choices.first().directory
        assertEquals(listOf("example.com/pc/cmd/api", "example.com/pc/store"), GoPackageChoices.forCommand(choices, GoCommand.TEST, api).map { it.label })
        assertEquals("C:/elsewhere/x.go", GoPackageChoices.forCommand(choices, GoCommand.RUN, "C:/elsewhere/x.go").first().label)
        assertTrue(GoPackageChoices.sameDirectory("C:\\Work\\pc\\", "c:/work/pc"))
    }

    fun testTheFilesTableReadsAndWritesTheLines() {
        val table = GoSshFilesTable(project) { null }
        table.text = "# keys\napp.yaml\ncerts/ca.pem = conf/ca.pem\n"
        assertEquals("app.yaml\ncerts/ca.pem=conf/ca.pem", table.text)
        table.text = null
        assertNull(table.text)
    }
}
