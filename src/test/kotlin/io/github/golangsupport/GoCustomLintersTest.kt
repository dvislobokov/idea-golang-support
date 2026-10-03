package io.github.golangsupport

import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lint.GoCustomLinters
import io.github.golangsupport.lint.GoLintDuplicates
import io.github.golangsupport.lint.GoLintIssue
import io.github.golangsupport.lint.GoSarifReader
import io.github.golangsupport.settings.GoCustomLinter
import io.github.golangsupport.settings.GoLinterDirectory
import io.github.golangsupport.settings.GoLinterFormat
import io.github.golangsupport.settings.GoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Custom linters without a process: macros of the command line, the two report formats, the messages, and what golangci-lint being optional means. */
class GoCustomLintersTest {
    private val sep = File.separator
    private val module = listOf("", "work", "shop").joinToString(sep)
    private val file = listOf(module, "store", "order.go").joinToString(sep)

    @Test fun macrosFromTheModuleRoot() {
        val context = GoCustomLinters.context(file, module, "example.com/shop", GoLinterDirectory.MODULE_ROOT)
        assertEquals(module, context.workDirectory)
        assertEquals(
            listOf("mylinter", "-json", "./store", "--file=$file", "$module${sep}store", module, "example.com/shop/store"),
            GoCustomLinters.expand("mylinter -json \$Package\$ --file=\$FilePath\$ \$FileDir\$ \$ModuleDir\$ \$ImportPath\$", context),
        )
    }

    @Test fun macrosFromTheFileDirectory() {
        val context = GoCustomLinters.context(file, module, "example.com/shop", GoLinterDirectory.FILE_DIRECTORY)
        assertEquals("$module${sep}store", context.workDirectory)
        assertEquals(listOf("vet", "."), GoCustomLinters.expand("vet \$Package\$", context))
    }

    @Test fun aFileAtTheModuleRootAndOutsideAnyModule() {
        val atRoot = GoCustomLinters.context("$module${sep}main.go", module, "example.com/shop", GoLinterDirectory.MODULE_ROOT)
        assertEquals(".", atRoot.packagePattern)
        assertEquals("example.com/shop", atRoot.importPath)
        val loose = GoCustomLinters.context(file, null, null, GoLinterDirectory.MODULE_ROOT)
        assertEquals("$module${sep}store", loose.moduleDir)
        assertEquals(".", loose.packagePattern)
        assertEquals(".", loose.importPath)
    }

    @Test fun quotedArgumentsWithSpacesStayWhole() {
        val spaced = listOf("", "my work", "a.go").joinToString(sep)
        val context = GoCustomLinters.context(spaced, null, null, GoLinterDirectory.FILE_DIRECTORY)
        assertEquals(listOf("lint", "--config", "a b.yml", spaced), GoCustomLinters.expand("lint --config \"a b.yml\" \$FilePath\$", context))
    }

    @Test fun aRelativeExecutableIsOfTheWorkingDirectory() {
        assertEquals(File(module, "tools/lint.cmd").path, GoCustomLinters.executable("tools/lint.cmd", module))
        assertEquals("staticcheck", GoCustomLinters.executable("staticcheck", module))
    }

    @Test fun golangciJsonBecomesFindingsTaggedWithTheLinter() {
        val stdout = """
            warning: something the tool says first
            {"Issues":[{"FromLinter":"errcheck","Text":"Error return value is not checked","Severity":"","Pos":{"Filename":"store/order.go","Line":12,"Column":9}},
            {"FromLinter":"","Text":"custom rule","Severity":"error","Pos":{"Filename":"store/order.go","Line":3,"Column":0}}],"Report":{}}
        """.trimIndent().replace(",\n{", ",{")
        val issues = GoCustomLinters.parse(GoLinterFormat.GOLANGCI_JSON, stdout)!!
        assertEquals(2, issues.size)
        val first = GoCustomLinters.finding("mylinter", issues[0])
        assertEquals("[mylinter] errcheck: Error return value is not checked", first.message)
        // //nolint: and the duplicates table go by the linter inside the report
        assertEquals("errcheck", first.key)
        val second = GoCustomLinters.finding("mylinter", issues[1])
        assertEquals("[mylinter] custom rule", second.message)
        assertEquals("mylinter", second.key)
        assertTrue(second.issue.isError)
    }

    @Test fun aPrettyPrintedReportIsReadWhole() {
        val issues = GoCustomLinters.parse(GoLinterFormat.GOLANGCI_JSON, "{\n  \"Issues\": [\n    {\"Text\": \"x\", \"Pos\": {\"Filename\": \"a.go\", \"Line\": 2}}\n  ]\n}\n")!!
        assertEquals(listOf(GoLintIssue("a.go", 2, 0, "x", "", false)), issues)
    }

    @Test fun noReportIsAFailureAnEmptyReportIsNot() {
        assertNull(GoCustomLinters.parse(GoLinterFormat.GOLANGCI_JSON, "panic: something"))
        assertEquals(emptyList<GoLintIssue>(), GoCustomLinters.parse(GoLinterFormat.GOLANGCI_JSON, """{"Issues":[]}"""))
        assertEquals(emptyList<GoLintIssue>(), GoCustomLinters.parse(GoLinterFormat.GOLANGCI_JSON, """{"Issues":null}"""))
        assertNull(GoCustomLinters.parse(GoLinterFormat.SARIF, """{"Issues":[]}"""))
    }

    @Test fun sarifResultsWithAbsoluteAndRelativeUris() {
        val absolute = File(module, "store/order.go").absoluteFile
        val sarif = """
            {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"semgrep"}},"results":[
              {"ruleId":"go.no-panic","level":"error","message":{"text":"no panics"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"${absolute.toURI()}"},"region":{"startLine":7,"startColumn":3,"endColumn":8}}}]},
              {"ruleId":"style","message":{"text":"long line"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"store/my%20order.go","uriBaseId":"%SRCROOT%"},"region":{"startLine":9}}}]},
              {"ruleId":"nowhere","message":{"text":"no location"}}
            ]}]}
        """.trimIndent()
        val issues = GoSarifReader.parse(sarif)!!
        assertEquals(2, issues.size)
        assertEquals(GoLintIssue(absolute.path, 7, 3, "no panics", "", true, "go.no-panic"), issues[0])
        assertEquals(GoLintIssue("store/my order.go", 9, 0, "long line", "", false, "style"), issues[1])
        assertEquals("[sg] go.no-panic: no panics", GoCustomLinters.finding("sg", issues[0]).message)
        assertEquals("sg", GoCustomLinters.finding("sg", issues[0]).key)
        assertNull(GoSarifReader.parse("not json"))
    }

    @Test fun theDuplicatesTable() {
        assertEquals(listOf("GoUncheckedError"), GoLintDuplicates.nativeInspections("errcheck", "Error return value is not checked"))
        assertEquals(listOf("GoIneffectualAssignment"), GoLintDuplicates.nativeInspections("ineffassign", "ineffectual assignment to err"))
        assertEquals(listOf("GoUnusedVariable", "GoUnusedParameter"), GoLintDuplicates.nativeInspections("unused", "var x is unused"))
        assertEquals(listOf("GoPrintf"), GoLintDuplicates.nativeInspections("govet", "printf: fmt.Sprintf format %d has arg s of wrong type string"))
        // the rest of govet, and linters the table does not know, are never dropped
        assertEquals(emptyList<String>(), GoLintDuplicates.nativeInspections("govet", "fieldalignment: struct of size 24 could be 16"))
        assertEquals(emptyList<String>(), GoLintDuplicates.nativeInspections("mylinter", "anything"))
    }

    @Test fun golangciLintIsOffByDefaultAndNotAskedForAtStart() {
        assertFalse(GoSettings.Settings().golangciLint)
        assertTrue(GoSettings.Settings().customLinters.isEmpty())
        assertFalse(GoTool.GOLANGCI_LINT in GoTool.offeredAtStart(golangciLint = false))
        assertTrue(GoTool.GOLANGCI_LINT in GoTool.offeredAtStart(golangciLint = true))
        assertTrue(GoTool.GOPLS in GoTool.offeredAtStart(golangciLint = false) && GoTool.DELVE in GoTool.offeredAtStart(golangciLint = false))
        assertFalse(GoTool.GOVULNCHECK in GoTool.offeredAtStart(golangciLint = true))
    }

    @Test fun aRowRunsOnlyWhenEnabledAndFilledIn() {
        assertTrue(GoCustomLinter("a", "a \$FilePath\$").isRunnable)
        assertFalse(GoCustomLinter("a", "a", enabled = false).isRunnable)
        assertFalse(GoCustomLinter("", "a").isRunnable)
        assertFalse(GoCustomLinter("a", " ").isRunnable)
    }
}
