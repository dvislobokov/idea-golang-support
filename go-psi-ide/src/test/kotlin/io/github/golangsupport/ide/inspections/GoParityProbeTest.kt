package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.ide.inspections.bugs.GoAssignmentToReceiverInspection
import io.github.golangsupport.ide.inspections.bugs.GoDeferGoInspection
import io.github.golangsupport.ide.inspections.bugs.GoImportUsedAsNameInspection
import io.github.golangsupport.ide.inspections.bugs.GoIrregularIotaInspection
import io.github.golangsupport.ide.inspections.bugs.GoMixedReceiverTypesInspection
import io.github.golangsupport.ide.inspections.bugs.GoReservedWordUsedAsNameInspection
import io.github.golangsupport.ide.inspections.bugs.GoTypeAssertionOnErrorsInspection
import io.github.golangsupport.ide.inspections.redundancy.GoEmptyDeclarationInspection
import io.github.golangsupport.ide.inspections.redundancy.GoPreferNilSliceInspection
import io.github.golangsupport.ide.inspections.redundancy.GoRedundantCommaInspection
import io.github.golangsupport.ide.inspections.redundancy.GoRedundantImportAliasInspection
import io.github.golangsupport.ide.inspections.redundancy.GoRedundantParensInspection
import io.github.golangsupport.ide.inspections.redundancy.GoRedundantSemicolonInspection
import io.github.golangsupport.ide.inspections.redundancy.GoRedundantTypeDeclInCompositeLitInspection
import io.github.golangsupport.ide.inspections.redundancy.GoUnusedTypeParameterInspection
import io.github.golangsupport.ide.inspections.redundancy.GoVarAndConstTypeMayBeOmittedInspection
import io.github.golangsupport.ide.inspections.style.GoCommentLeadingSpaceInspection
import io.github.golangsupport.ide.inspections.style.GoCommentStartInspection
import io.github.golangsupport.ide.inspections.style.GoErrorStringFormatInspection
import io.github.golangsupport.ide.inspections.style.GoExportedOwnDeclarationInspection
import io.github.golangsupport.ide.inspections.style.GoNameStartsWithPackageNameInspection
import io.github.golangsupport.ide.inspections.style.GoReceiverNamesInspection
import io.github.golangsupport.ide.inspections.style.GoRedundantElseInIfInspection
import io.github.golangsupport.ide.inspections.style.GoSnakeCaseUsageInspection
import io.github.golangsupport.ide.inspections.style.GoStructInitializationWithoutFieldNamesInspection
import io.github.golangsupport.ide.inspections.style.GoTypeParameterInLowerCaseInspection
import io.github.golangsupport.ide.inspections.style.GoUnitSpecificDurationSuffixInspection
import io.github.golangsupport.ide.inspections.style.GoUnsortedImportInspection
import java.io.File

/**
 * The G7 inspections over the GoLand probe files (tools/ui-robot/goland/probe, playground/store/order.go) report exactly what GoLand's
 * highlighting dumps (docs/goland-analysis/dumps/highlight-*.txt) show for the checks whose message is GoLand's own, and nothing else:
 * no false positives where GoLand is quiet.
 */
class GoParityProbeTest : GoParityInspectionTestBase() {

    private fun tools(): Array<LocalInspectionTool> = arrayOf(
        GoCommentLeadingSpaceInspection(), GoCommentStartInspection(), GoErrorStringFormatInspection(), GoExportedOwnDeclarationInspection(),
        GoNameStartsWithPackageNameInspection(), GoReceiverNamesInspection(), GoRedundantElseInIfInspection(), GoStructInitializationWithoutFieldNamesInspection(),
        GoTypeParameterInLowerCaseInspection(), GoUnitSpecificDurationSuffixInspection(), GoUnsortedImportInspection(), GoSnakeCaseUsageInspection(),
        GoEmptyDeclarationInspection(), GoPreferNilSliceInspection(), GoRedundantCommaInspection(), GoRedundantImportAliasInspection(),
        GoRedundantSemicolonInspection(), GoRedundantTypeDeclInCompositeLitInspection(), GoVarAndConstTypeMayBeOmittedInspection(),
        GoUnusedTypeParameterInspection(), GoRedundantParensInspection(), GoDeferGoInspection(), GoImportUsedAsNameInspection(),
        GoIrregularIotaInspection(), GoMixedReceiverTypesInspection(), GoReservedWordUsedAsNameInspection(), GoTypeAssertionOnErrorsInspection(),
        GoAssignmentToReceiverInspection(),
    )

    private val repo: File get() = File(GoIdeTestBase.testDataRoot()).parentFile

    private fun read(relative: String) = File(repo, relative).readText().replace("\r\n", "\n")

    /** `line:col-line:col «text» message` of every problem the G7 inspections report in [path] of the temp project. */
    private fun findings(path: String): List<String> {
        myFixture.enableInspections(*tools())
        myFixture.configureFromTempProjectFile(path)
        val document = myFixture.editor.document
        val ids = tools().map { it.shortName }.toSet()
        return myFixture.doHighlighting().filter { it.inspectionToolId in ids }.map { info ->
            val sl = document.getLineNumber(info.startOffset)
            val el = document.getLineNumber(info.endOffset)
            val sc = info.startOffset - document.getLineStartOffset(sl) + 1
            val ec = info.endOffset - document.getLineStartOffset(el) + 1
            "${sl + 1}:$sc-${el + 1}:$ec «${document.getText(com.intellij.openapi.util.TextRange(info.startOffset, info.endOffset))}» ${info.description}"
        }.sorted()
    }

    /** The dump lines of [dump] whose message is one of [messages], in the same form as [findings]. */
    private fun expected(dump: String, vararg messages: String): List<String> = read("docs/goland-analysis/dumps/$dump").lines().mapNotNull { line ->
        val m = DUMP_LINE.find(line) ?: return@mapNotNull null
        val message = m.groupValues[3].removeSuffix(" STRIPE").trim()
        if (message !in messages) null else "${m.groupValues[1]} «${m.groupValues[2]}» $message"
    }.distinct().sorted()

    private fun addProbe() {
        myFixture.addFileToProject("probe/analysis.go", read("tools/ui-robot/goland/probe/analysis.go"))
        myFixture.addFileToProject("probe/analysis_test.go", read("tools/ui-robot/goland/probe/analysis_test.go"))
        myFixture.addFileToProject("probe/probeerr/broken.go", read("tools/ui-robot/goland/probe/probeerr/broken.go"))
        myFixture.addFileToProject("store/order.go", read("playground/store/order.go"))
    }

    fun testAnalysisGoIsQuietLikeGoLand() {
        addProbe()
        assertEquals(expected("highlight-internal-probe-analysis.txt", *GOLAND_MESSAGES), findings("probe/analysis.go"))
    }

    fun testAnalysisTestGoReportsTheUnkeyedTableRowsLikeGoLand() {
        addProbe()
        val want = expected("highlight-internal-probe-analysis_test.txt", *GOLAND_MESSAGES)
        assertEquals(2, want.size)
        assertEquals(want, findings("probe/analysis_test.go"))
    }

    fun testBrokenGoIsQuietLikeGoLand() {
        addProbe()
        assertEquals(expected("highlight-internal-probeerr-broken.txt", *GOLAND_MESSAGES), findings("probe/probeerr/broken.go"))
    }

    fun testOrderGoReportsTheMeaninglessCommentLikeGoLand() {
        addProbe()
        val want = expected("highlight-store-order.txt", *GOLAND_MESSAGES)
        assertEquals(1, want.size)
        assertEquals(want, findings("store/order.go"))
    }

    // --- G10: the second probe (tools/ui-robot/goland/probe/probe2), texts and ranges of GoLand 2026.2.3 ---

    private fun addProbe2() {
        for (name in listOf("style.go", "other.go", "iota.go", "iota2.go", "iota3.go")) {
            myFixture.addFileToProject("probe2/$name", read("tools/ui-robot/goland/probe/probe2/$name"))
        }
    }

    /** Our findings in [path] whose message is one GoLand gives too (by [G10_MESSAGES]): INFORMATION ones included, as the dumps list them. */
    private fun probe2Findings(path: String): List<String> = findings(path).filter { line -> G10_MESSAGES.any { it.containsMatchIn(line.substringAfter("» ")) } }
        .map { line -> // the dumps cut the highlighted text at 50 characters
            val text = line.substringAfter("«").substringBeforeLast("»")
            line.replace("«$text»", "«${text.take(50)}»")
        }

    /** The dump lines of [dump] with a message of [G10_MESSAGES]. */
    private fun probe2Expected(dump: String): List<String> = read("docs/goland-analysis/dumps/$dump").lines().mapNotNull { line ->
        val m = DUMP_LINE.find(line) ?: return@mapNotNull null
        val message = m.groupValues[3].removeSuffix(" STRIPE").trim()
        if (G10_MESSAGES.none { it.containsMatchIn(message) }) null else "${m.groupValues[1]} «${m.groupValues[2]}» $message"
    }.distinct().sorted()

    fun testProbe2StyleGoGivesGoLandsTextsAndRanges() {
        addProbe2()
        val want = probe2Expected("highlight-internal-probe2-style.txt")
        assertTrue(want.toString(), want.size > 40)
        assertEquals(want.joinToString("\n"), probe2Findings("probe2/style.go").joinToString("\n"))
    }

    fun testProbe2OtherGoComparesReceiversAcrossFiles() {
        addProbe2()
        assertEquals(probe2Expected("highlight-internal-probe2-other.txt").joinToString("\n"), probe2Findings("probe2/other.go").joinToString("\n"))
    }

    fun testProbe2IotaFilesGiveGoLandsFindings() {
        addProbe2()
        assertEquals(probe2Expected("highlight-internal-probe2-iota.txt").joinToString("\n"), probe2Findings("probe2/iota.go").joinToString("\n"))
        assertEquals(probe2Expected("highlight-internal-probe2-iota2.txt").joinToString("\n"), probe2Findings("probe2/iota2.go").joinToString("\n"))
    }

    /** iota3.go has no dump; GoLand's findings there (PLAN.md G10): `c = iota`, `c1, cc1 = iota, iota`, `l Weekday = iota`. */
    fun testProbe2Iota3GivesGoLandsFindings() {
        addProbe2()
        assertEquals(
            listOf(
                "6:2-6:10 «c = iota» Irregular usage of 'iota'",
                "12:2-12:22 «c1, cc1 = iota, iota» Irregular usage of 'iota'",
                "30:2-30:18 «l Weekday = iota» Irregular usage of 'iota'",
            ).sorted(),
            probe2Findings("probe2/iota3.go"),
        )
    }

    private companion object {
        /** GoLand's texts of the G7 inspections re-captured in G10 (dumps `highlight-internal-probe2-*.txt`). */
        val G10_MESSAGES = listOf(
            "^Imports are not sorted$", "^Redundant alias$", "^Comment should have the following format '.*' \\(with an optional leading article\\)$",
            "^Use camel case instead of snake case$", "^Exported (variable|constant) '.*' should have its own declaration$", "^Name starts with the package name$",
            "^Receiver names are different$", "^Receiver has a generic name$", "^Struct \\w+ has methods on both value and pointer receivers\\.",
            "^Assignment to the method receiver", "^Redundant parentheses$", "^Redundant 'else' in 'if'$", "^Redundant semicolon$", "^Type can be omitted$",
            "^Empty slice declaration using a literal$", "^Redundant comma$", "^Redundant type$", "^Empty declaration '", "^Error string should not",
            "^Type assertion on errors fails on wrapped errors$", "^(defer|go) should not call", "collides with imported package name$",
            "collides with the 'builtin' ", "^Unit-specific suffix '", "^Unused type parameter '", "^Fields are assigned without explicit names$",
            "^Irregular usage of 'iota'$",
        ).map(::Regex)

        /** Messages our G7 inspections share verbatim with GoLand (seen in the dumps). */
        val GOLAND_MESSAGES = arrayOf("Fields are assigned without explicit names", "Comment should be meaningful or it should be removed")

        // `  22:3-22:20  [KEY] layer 4000  «{"empty", nil, 0}»  TIP: message`
        val DUMP_LINE = Regex("^\\s+(\\d+:\\d+-\\d+:\\d+)\\s+\\[[^]]*]\\s+layer \\d+\\s+«(.*)»\\s+TIP: (.*)$")
    }
}
