package io.github.golangsupport.ide.formatter

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage

/** Code Style | Go: the Imports options of Optimize Imports and the chop-down options of Reformat Code (PLAN.md G8). */
class GoCodeStyleOptionsTest : GoSemanticIdeTestBase() {

    private fun withStyle(change: (GoCodeStyleSettings, CodeStyleSettings) -> Unit, body: () -> Unit) {
        CodeStyle.runWithLocalSettings(project, CodeStyle.getSettings(project)) { settings: CodeStyleSettings ->
            change(settings.getCustomSettings(GoCodeStyleSettings::class.java), settings)
            body()
        }
    }

    private fun optimize(before: String, after: String, change: (GoCodeStyleSettings) -> Unit) = withStyle({ go, _ -> change(go) }) {
        myFixture.configureByText("a.go", before)
        OptimizeImportsProcessor(project, myFixture.file).run()
        myFixture.checkResult(after)
    }

    private val mixed = "package a\n\nimport (\n\t\"github.com/x/y\"\n\t\"os\"\n\n\t\"fmt\"\n)\n\nvar _ = fmt.Sprint(os.Args, y.Z)\n"

    fun testGoimportsSortingIsTheDefault() = optimize(mixed, "package a\n\nimport (\n\t\"fmt\"\n\t\"os\"\n\n\t\"github.com/x/y\"\n)\n\nvar _ = fmt.Sprint(os.Args, y.Z)\n") {}

    fun testGofmtSortingKeepsTheGroupsWrittenByHand() = optimize(mixed, "package a\n\nimport (\n\t\"github.com/x/y\"\n\t\"os\"\n\n\t\"fmt\"\n)\n\nvar _ = fmt.Sprint(os.Args, y.Z)\n") {
        it.IMPORT_SORTING = GoCodeStyleSettings.SORT_GOFMT
    }

    fun testNoSortingKeepsTheOrder() {
        val text = "package a\n\nimport (\n\t\"os\"\n\t\"fmt\"\n)\n\nvar _ = fmt.Sprint(os.Args)\n"
        optimize(text, text) { it.IMPORT_SORTING = GoCodeStyleSettings.SORT_NONE }
    }

    fun testStandardLibraryNotGroupedApart() = optimize(mixed, "package a\n\nimport (\n\t\"fmt\"\n\t\"github.com/x/y\"\n\t\"os\"\n)\n\nvar _ = fmt.Sprint(os.Args, y.Z)\n") {
        it.IMPORT_GROUP_STDLIB = false
    }

    fun testLocalPrefixesFormTheLastGroup() = optimize(
        "package a\n\nimport (\n\t\"corp.io/lib\"\n\t\"github.com/x/y\"\n\t\"os\"\n)\n\nvar _ = []any{lib.A, y.Z, os.Args}\n",
        "package a\n\nimport (\n\t\"os\"\n\n\t\"github.com/x/y\"\n\n\t\"corp.io/lib\"\n)\n\nvar _ = []any{lib.A, y.Z, os.Args}\n",
    ) { it.IMPORT_LOCAL_PREFIXES = "corp.io" }

    fun testAllImportsMovedToOneDeclaration() = optimize(
        "package a\n\nimport \"os\"\n\nimport (\n\t\"fmt\"\n)\n\nvar _ = fmt.Sprint(os.Args)\n",
        "package a\n\nimport (\n\t\"fmt\"\n\t\"os\"\n)\n\nvar _ = fmt.Sprint(os.Args)\n",
    ) { it.IMPORT_ONE_DECLARATION = true }

    fun testRedundantAliasesRemoved() = optimize(
        "package a\n\nimport (\n\tfmt \"fmt\"\n\tstr \"strings\"\n)\n\nvar _ = fmt.Sprint(str.ToUpper(\"\"))\n",
        "package a\n\nimport (\n\t\"fmt\"\n\tstr \"strings\"\n)\n\nvar _ = fmt.Sprint(str.ToUpper(\"\"))\n",
    ) { it.IMPORT_REMOVE_REDUNDANT_ALIASES = true }

    fun testPureHelpers() {
        assertEquals("import (\n\t\"a\"\n\t// c\n\tx \"b\"\n\t\"c\"\n)", GoImportGroups.merge(listOf("import \"a\"", "import (\n\t// c\n\tx \"b\"\n)", "import \"c\"")))
        assertNull(GoImportGroups.merge(listOf("import \"a\"")))
        assertEquals("\"fmt\" // why", GoImportGroups.withoutRedundantAlias("fmt \"fmt\" // why", "fmt"))
        assertNull(GoImportGroups.withoutRedundantAlias("f \"fmt\"", "fmt"))
        assertNull(GoImportGroups.withoutRedundantAlias("_ \"embed\"", "_"))
        assertEquals(GoImportGroups.Group.THIRD_PARTY, GoImportGroups.groupOf("os", emptyList(), groupStdlib = false))
    }

    private fun reformat(before: String, after: String, change: (GoCodeStyleSettings) -> Unit) = withStyle({ go, settings ->
        change(go)
        settings.getCommonSettings(GoLanguage).RIGHT_MARGIN = 40
    }) {
        myFixture.configureByText("a.go", before)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        assertEquals(after, myFixture.editor.document.text)
    }

    private val long = "package a\n\nfunc join(first, second, third string) string {\n\treturn first + second + third\n}\n\nvar v = join(\"aaaaaaaaaa\", \"bbbbbbbbbb\", \"cccccccccc\")\n"

    fun testLongCallArgumentsChoppedDown() = reformat(long,
        "package a\n\nfunc join(first, second, third string) string {\n\treturn first + second + third\n}\n\nvar v = join(\n\t\"aaaaaaaaaa\",\n\t\"bbbbbbbbbb\",\n\t\"cccccccccc\",\n)\n",
    ) { it.CHOP_DOWN_CALL_ARGUMENTS = true }

    fun testLongParametersChoppedDown() = reformat(
        "package a\n\nfunc join(first string, second string, third string) {}\n",
        "package a\n\nfunc join(\n\tfirst string,\n\tsecond string,\n\tthird string,\n) {}\n",
    ) { it.CHOP_DOWN_PARAMETERS = true }

    fun testLongCompositeLiteralChoppedDown() = reformat(
        "package a\n\nvar names = []string{\"aaaaaaaaaa\", \"bbbbbbbbbb\", \"cccc\"}\n",
        "package a\n\nvar names = []string{\n\t\"aaaaaaaaaa\",\n\t\"bbbbbbbbbb\",\n\t\"cccc\",\n}\n",
    ) { it.CHOP_DOWN_COMPOSITE_LITERALS = true }

    fun testNothingChoppedByDefault() = reformat(long, long) {}
}
