package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lint.GoVulnOutput
import io.github.golangsupport.lint.GoVulnService
import io.github.golangsupport.lint.GoVulnerableCodeUsagesInspection
import io.github.golangsupport.lint.GoVulnerablePackageImportInspection
import io.github.golangsupport.settings.GoSettings

/** GoVulnerablePackageImport and GoVulnerableCodeUsages over a govulncheck report put into [GoVulnService] (no govulncheck is run). */
class GoVulnInspectionsTest : BasePlatformTestCase() {
    private var server = true
    private var check = false

    override fun setUp() {
        super.setUp()
        server = GoSettings.getInstance().languageServerEnabled
        check = GoSettings.getInstance().vulnerabilityCheck
        GoSettings.getInstance().languageServerEnabled = false
        // no background run from the inspections
        GoSettings.getInstance().vulnerabilityCheck = false
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = server
            GoSettings.getInstance().vulnerabilityCheck = check
            myFixture.findFileInTempDir("go.mod")?.parent?.let { GoVulnService.getInstance(project).putForTests(it, null) }
        } finally {
            super.tearDown()
        }
    }

    private val source = """
        package web

        import (
        	"fmt"
        	"golang.org/x/text/language"
        )

        func lang(h string) string {
        	tags, _, _ := language.ParseAcceptLanguage(h)
        	return fmt.Sprint(tags)
        }
    """.trimIndent() + "\n"

    private fun report(line: Int, column: Int) = GoVulnOutput.parse(
        """
        {"config": {"protocol_version": "v1.0.0"}}
        {"osv": {"id": "GO-2022-1059", "summary": "Denial of service via crafted Accept-Language header"}}
        {"finding": {"osv": "GO-2022-1059", "fixed_version": "v0.3.8", "trace": [{"module": "golang.org/x/text", "version": "v0.3.7", "package": "golang.org/x/text/language"}]}}
        {"finding": {"osv": "GO-2022-1059", "fixed_version": "v0.3.8", "trace": [
          {"module": "golang.org/x/text", "version": "v0.3.7", "package": "golang.org/x/text/language", "function": "ParseAcceptLanguage"},
          {"module": "example.com/app", "package": "example.com/app/web", "function": "lang", "position": {"filename": "web/lang.go", "line": $line, "column": $column}}]}}
        """.trimIndent(),
    )!!

    private fun configure(): Pair<Int, Int> {
        val mod = myFixture.addFileToProject("go.mod", "module example.com/app\n\ngo 1.22\n\nrequire golang.org/x/text v0.3.7\n")
        val file = myFixture.addFileToProject("web/lang.go", source)
        val lines = source.split('\n')
        val line = lines.indexOfFirst { "ParseAcceptLanguage(" in it }
        val column = lines[line].indexOf("ParseAcceptLanguage(") + "ParseAcceptLanguage".length + 1
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return (line + 1 to column).also { (l, c) -> GoVulnService.getInstance(project).putForTests(mod.virtualFile.parent, report(l, c)) }
    }

    fun testVulnerableImportIsReported() {
        myFixture.enableInspections(GoVulnerablePackageImportInspection::class.java)
        configure()
        val highlights = myFixture.doHighlighting().filter { it.description?.startsWith("Package 'golang.org/x/text/language'") == true }
        assertEquals(1, highlights.size)
        assertEquals("Package 'golang.org/x/text/language' of module golang.org/x/text@v0.3.7 is affected by GO-2022-1059: Denial of service via crafted Accept-Language header",
            highlights[0].description)
        assertEquals("\"golang.org/x/text/language\"", myFixture.editor.document.getText(highlights[0].let { com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset) }))
        myFixture.editor.caretModel.moveToOffset(highlights[0].startOffset + 2)
        assertNotNull(myFixture.availableIntentions.firstOrNull { it.text == "Upgrade golang.org/x/text to v0.3.8" })
    }

    fun testCallOfVulnerableFunctionIsReported() {
        myFixture.enableInspections(GoVulnerableCodeUsagesInspection::class.java)
        configure()
        val highlights = myFixture.doHighlighting().filter { it.description?.startsWith("Call to vulnerable function") == true }
        assertEquals(listOf("Call to vulnerable function language.ParseAcceptLanguage (GO-2022-1059)"), highlights.map { it.description })
        assertEquals("ParseAcceptLanguage", myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(highlights[0].startOffset, highlights[0].endOffset)))
    }

    fun testChangedLineShowsNothing() {
        myFixture.enableInspections(GoVulnerableCodeUsagesInspection::class.java)
        val (line, column) = configure()
        // the run named another line: no call of that name there
        GoVulnService.getInstance(project).putForTests(myFixture.findFileInTempDir("go.mod")!!.parent, report(line + 1, column))
        assertEmpty(myFixture.doHighlighting().filter { it.description?.startsWith("Call to vulnerable function") == true })
    }

    fun testNothingWithoutAReport() {
        myFixture.enableInspections(GoVulnerablePackageImportInspection::class.java, GoVulnerableCodeUsagesInspection::class.java)
        myFixture.addFileToProject("go.mod", "module example.com/app\n")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("web/lang.go", source).virtualFile)
        assertEmpty(myFixture.doHighlighting().filter { it.description?.contains("GO-2022-1059") == true })
    }
}
