package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.formatter.GoImportGroups
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider

/** goimports groups: Optimize Imports regroups ([GoImportGroups.regroup]), auto-import puts a path into its group ([GoImportInserter]). */
class GoImportGroupingTest : GoSemanticIdeTestBase() {
    private val locals = listOf("example.com/me")

    fun testGroupOf() {
        assertEquals(GoImportGroups.Group.CGO, GoImportGroups.groupOf("C", locals))
        assertEquals(GoImportGroups.Group.STANDARD, GoImportGroups.groupOf("net/http", locals))
        assertEquals(GoImportGroups.Group.THIRD_PARTY, GoImportGroups.groupOf("github.com/x/y", locals))
        assertEquals(GoImportGroups.Group.LOCAL, GoImportGroups.groupOf("example.com/me/util", locals))
        assertEquals(GoImportGroups.Group.LOCAL, GoImportGroups.groupOf("example.com/me", locals))
        assertEquals(GoImportGroups.Group.THIRD_PARTY, GoImportGroups.groupOf("example.com/meter", locals))
        // a module path without a dot is local, not the standard library
        assertEquals(GoImportGroups.Group.LOCAL, GoImportGroups.groupOf("myapp/db", listOf("myapp")))
    }

    fun testRegroupStandardThirdPartyLocal() {
        val before = "import (\n\t\"example.com/me/util\"\n\t\"github.com/x/y\"\n\t\"os\"\n\n\t\"fmt\"\n\t\"example.com/me/db\"\n)"
        assertEquals(
            "import (\n\t\"fmt\"\n\t\"os\"\n\n\t\"github.com/x/y\"\n\n\t\"example.com/me/db\"\n\t\"example.com/me/util\"\n)",
            GoImportGroups.regroup(before, locals),
        )
    }

    fun testRegroupKeepsCommentsNamedImportsAndC() {
        val before = "import (\n" +
            "\t// the router\n" +
            "\tmux \"github.com/gorilla/mux\"\n" +
            "\t_ \"embed\" // for go:embed\n" +
            "\t\"C\"\n" +
            "\t. \"strings\"\n" +
            "\t// trailing\n" +
            ")"
        assertEquals(
            "import (\n\t\"C\"\n\n\t_ \"embed\" // for go:embed\n\t. \"strings\"\n\n\t// the router\n\tmux \"github.com/gorilla/mux\"\n\t// trailing\n)",
            GoImportGroups.regroup(before, locals),
        )
    }

    fun testRegroupLeavesWhatItCannotOrNeedNotChange() {
        // already in goimports order
        assertNull(GoImportGroups.regroup("import (\n\t\"fmt\"\n\n\t\"github.com/x/y\"\n)", locals))
        // one spec in parentheses keeps its form
        assertNull(GoImportGroups.regroup("import (\n\t\"github.com/x/y\"\n)", locals))
        // two specs on one line: left to gofmt's sort
        assertNull(GoImportGroups.regroup("import (\n\t\"os\"; \"fmt\"\n)", locals))
        assertNull(GoImportGroups.regroup("import \"fmt\"", locals))
    }

    fun testOptimizeImportsRegroupsWithTheMainModule() {
        useMainModule("example.com/me")
        myFixture.configureByText(
            "a.go",
            "package a\n\nimport (\n\t_ \"example.com/me/util\"\n\tyaml \"gopkg.in/yaml.v3\"\n\t\"strings\"\n\n\t\"fmt\"\n)\n\n" +
                "var _ = yaml.Marshal\n\nfunc f() { fmt.Println(strings.ToUpper(\"a\")) }\n",
        )
        OptimizeImportsProcessor(project, myFixture.file).run()
        myFixture.checkResult(
            "package a\n\nimport (\n\t\"fmt\"\n\t\"strings\"\n\n\tyaml \"gopkg.in/yaml.v3\"\n\n\t_ \"example.com/me/util\"\n)\n\n" +
                "var _ = yaml.Marshal\n\nfunc f() { fmt.Println(strings.ToUpper(\"a\")) }\n",
        )
    }

    fun testOptimizeImportsKeepsASingleImport() {
        myFixture.configureByText("a.go", "package a\n\nimport \"fmt\"\n\nfunc f() { fmt.Println() }\n")
        OptimizeImportsProcessor(project, myFixture.file).run()
        myFixture.checkResult("package a\n\nimport \"fmt\"\n\nfunc f() { fmt.Println() }\n")
    }

    fun testInsertIntoTheGroupOfThePath() {
        useMainModule("example.com/me")
        val text = "package a\n\nimport (\n\t\"fmt\"\n\n\t\"github.com/x/y\"\n)\n"
        assertEquals("package a\n\nimport (\n\t\"fmt\"\n\n\t\"github.com/x/y\"\n\n\t\"example.com/me/util\"\n)\n", insert(text, "example.com/me/util"))
        assertEquals("package a\n\nimport (\n\t\"fmt\"\n\n\t\"github.com/a/b\"\n\t\"github.com/x/y\"\n)\n", insert(text, "github.com/a/b"))
        assertEquals("package a\n\nimport (\n\t\"fmt\"\n\t\"os\"\n\n\t\"github.com/x/y\"\n)\n", insert(text, "os"))
        // the group is missing: made where it goes, with its blank line
        assertEquals(
            "package a\n\nimport (\n\t\"os\"\n\n\t\"github.com/x/y\"\n)\n",
            insert("package a\n\nimport (\n\t\"github.com/x/y\"\n)\n", "os"),
        )
        assertEquals(
            "package a\n\nimport (\n\t\"fmt\"\n\n\t\"github.com/x/y\"\n)\n",
            insert("package a\n\nimport (\n\t\"fmt\"\n)\n", "github.com/x/y"),
        )
        // a single import becomes a grouped declaration in goimports order
        assertEquals("package a\n\nimport (\n\t\"os\"\n\n\t\"example.com/me/util\"\n)\n", insert("package a\n\nimport \"example.com/me/util\"\n", "os"))
    }

    private fun insert(text: String, path: String): String {
        val file = myFixture.configureByText("a.go", text) as GoFile
        WriteCommandAction.runWriteCommandAction(project) { GoImportInserter.addImport(file, myFixture.editor.document, path) }
        return myFixture.editor.document.text
    }

    /** The module graph says the file is in the main module [path] (the light project has no go.mod on disk). */
    private fun useMainModule(path: String) {
        val module = GoModule(path, null, null, null, null, isMain = true)
        val graph = GoModuleGraph(listOf(module), listOf(module), null, false, null, emptyList(), GoModuleGraph.Source.PURE)
        project.replaceService(GoModuleGraphProvider::class.java, object : GoModuleGraphProvider {
            override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph = graph
        }, testRootDisposable)
    }
}
