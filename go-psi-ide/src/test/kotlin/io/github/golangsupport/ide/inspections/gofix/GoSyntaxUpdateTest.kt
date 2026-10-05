package io.github.golangsupport.ide.inspections.gofix

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoIdeTestBase

/** A stand-in for the Go fix inspections (written elsewhere): flags every `interface` keyword, in the group the real ones use. */
class GoFixTestInspection : LocalInspectionTool() {
    override fun getShortName(): String = "GoFixTest"
    override fun getDisplayName(): String = "'interface{}' can be replaced with 'any' (test)"
    override fun getGroupDisplayName(): String = GoSyntaxUpdate.GROUP_NAME
    override fun getGroupPath(): Array<String> = arrayOf("Go", GoSyntaxUpdate.GROUP_NAME)
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!isOnTheFly) batchRuns++
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is LeafPsiElement && element.text == "interface") holder.registerProblem(element, "interface{} can be any")
            }
        }
    }

    companion object {
        /** Runs outside the daemon (`processFile`, the lens fallback). */
        @Volatile var batchRuns = 0
    }
}

/** An inspection of another group: never part of Update Syntax. */
class GoOtherTestInspection : LocalInspectionTool() {
    override fun getShortName(): String = "GoOtherTest"
    override fun getDisplayName(): String = "Other (test)"
    override fun getGroupDisplayName(): String = "Go"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element is LeafPsiElement && element.text == "interface") holder.registerProblem(element, "other")
        }
    }
}

/** The SYNTAX_UPDATE severity, the tools Update Syntax runs, the action's place, and the Batch syntax update / What's New lenses. */
class GoSyntaxUpdateTest : GoIdeTestBase() {

    private val withFinding = """
        package p

        func f(v interface{}) {}
    """.trimIndent()

    private val withoutFinding = """
        package p

        func f(v any) {}
    """.trimIndent()

    fun testSeverityIsRegisteredByName() {
        val level = HighlightDisplayLevel.find(GoSyntaxUpdateSeverity.NAME)
        assertNotNull("level=\"SYNTAX_UPDATE\" resolves", level)
        assertEquals(GoSyntaxUpdateSeverity.SEVERITY, level!!.severity)
        assertEquals(level, GoSyntaxUpdateSeverity.level())
        val registrar = SeverityRegistrar.getSeverityRegistrar(project)
        assertEquals(GoSyntaxUpdateSeverity.SEVERITY, registrar.getSeverity("SYNTAX_UPDATE"))
        assertTrue(GoSyntaxUpdateSeverity.SEVERITY < HighlightSeverity.WEAK_WARNING)
        assertTrue(GoSyntaxUpdateSeverity.SEVERITY > HighlightSeverity.INFORMATION)
        assertEquals("Syntax update", GoSyntaxUpdateSeverity.SEVERITY.displayName)
        assertEquals("3 syntax updates", GoSyntaxUpdateSeverity.SEVERITY.getCountMessage(3))
    }

    /** GoLand's value (seen live): 20, between TEXT ATTRIBUTES (11) and WEAK WARNING (200), in the registrar's order too. */
    fun testSeverityValueAndOrder() {
        assertEquals(20, GoSyntaxUpdateSeverity.SEVERITY.myVal)
        assertTrue(GoSyntaxUpdateSeverity.SEVERITY > HighlightSeverity.TEXT_ATTRIBUTES)
        assertTrue(GoSyntaxUpdateSeverity.SEVERITY < HighlightSeverity.GENERIC_SERVER_ERROR_OR_WARNING)
        val registrar = SeverityRegistrar.getSeverityRegistrar(project)
        assertTrue(registrar.compare(GoSyntaxUpdateSeverity.SEVERITY, HighlightSeverity.INFORMATION) > 0)
        assertTrue(registrar.compare(GoSyntaxUpdateSeverity.SEVERITY, HighlightSeverity.TEXT_ATTRIBUTES) > 0)
        assertTrue(registrar.compare(GoSyntaxUpdateSeverity.SEVERITY, HighlightSeverity.WEAK_WARNING) < 0)
        assertSame(AllIcons.Actions.Refresh, (GoSyntaxUpdateSeverity.INFO_TYPE as HighlightInfoType.Iconable).icon)
    }

    /** GoLand shows no lens in the file: the startup default turns both lenses off once, and a later choice of the user is kept. */
    fun testLensesAreOffByDefault() {
        val properties = PropertiesComponent.getInstance()
        val settings = CodeVisionSettings.getInstance()
        val ids = listOf(GoSyntaxUpdateLenses.BATCH_ID, GoSyntaxUpdateLenses.WHATS_NEW_ID)
        val before = properties.getBoolean(GoSyntaxUpdateLensDefaults.APPLIED_KEY) to ids.map { settings.isProviderEnabled(it) }
        try {
            properties.unsetValue(GoSyntaxUpdateLensDefaults.APPLIED_KEY)
            ids.forEach { settings.setProviderEnabled(it, true) }
            GoSyntaxUpdateLensDefaults.apply()
            assertEquals(listOf(false, false), ids.map { settings.isProviderEnabled(it) })
            settings.setProviderEnabled(GoSyntaxUpdateLenses.BATCH_ID, true)
            GoSyntaxUpdateLensDefaults.apply()
            assertTrue(settings.isProviderEnabled(GoSyntaxUpdateLenses.BATCH_ID))
        } finally {
            properties.setValue(GoSyntaxUpdateLensDefaults.APPLIED_KEY, before.first)
            ids.forEachIndexed { i, id -> settings.setProviderEnabled(id, before.second[i]) }
        }
    }

    fun testSelectionKeepsTheEnabledGoFixTools() {
        val tools = listOf(
            GoSyntaxUpdate.Tool("GoFixAny", listOf("Go", "Go fix"), true),
            GoSyntaxUpdate.Tool("GoFixMinMax", listOf("Go", "Go fix"), false),
            GoSyntaxUpdate.Tool("GoFixRangeInt", emptyList(), true),
            GoSyntaxUpdate.Tool("ModernizeLoop", listOf("Go", "Go fix"), true),
            GoSyntaxUpdate.Tool("GoUnusedImport", listOf("Go"), true),
            GoSyntaxUpdate.Tool("GoFixer", listOf("Other", "Go fixes"), true),
        )
        // GoFixer starts with the prefix: the short name alone is enough, as the ids of the group are GoFix*
        assertEquals(listOf("GoFixAny", "GoFixRangeInt", "GoFixer", "ModernizeLoop"), GoSyntaxUpdate.select(tools))
        assertEquals(emptyList<String>(), GoSyntaxUpdate.select(emptyList()))
    }

    fun testEnabledToolsOfTheProfile() {
        myFixture.enableInspections(GoFixTestInspection(), GoOtherTestInspection())
        val tools = GoSyntaxUpdate.enabledTools(project)
        assertEquals(listOf("GoFixTest"), tools.map { it.shortName })
        // a light test does not initialize the tools of a new profile (production does, as Run Inspection by Name relies on)
        val init = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        val profile = try { GoSyntaxUpdate.profileOf(project, tools) } finally { InspectionProfileImpl.INIT_INSPECTIONS = init }
        assertEquals(listOf("GoFixTest"), profile.getAllEnabledInspectionTools(project).map { it.shortName })
    }

    fun testActionIsInTheRefactorMenu() {
        val actions = ActionManager.getInstance()
        val action = actions.getAction("Go.UpdateSyntax")
        assertInstanceOf(action, GoUpdateSyntaxAction::class.java)
        assertEquals("Update Syntax...", action.templatePresentation.text)
        assertTrue((actions.getAction("RefactoringMenu") as DefaultActionGroup).childActionsOrStubs.any { actions.getId(it) == "Go.UpdateSyntax" })
    }

    private fun lenses(provider: DaemonBoundCodeVisionProvider): List<String> =
        // the daemon computes code vision under its progress indicator, and LocalInspectionTool.processFile requires one
        ProgressManager.getInstance().runProcess<List<Pair<TextRange, CodeVisionEntry>>>({ provider.computeForEditor(myFixture.editor, myFixture.file) }, EmptyProgressIndicator()).map { (range, entry) ->
            "${myFixture.file.text.substring(range.startOffset, range.endOffset)}: ${(entry as ClickableTextCodeVisionEntry).text}"
        }

    fun testLensesOverAFileWithFindings() {
        myFixture.enableInspections(GoFixTestInspection())
        myFixture.configureByText("a.go", withFinding)
        assertEquals(listOf("package p: Update syntax (1 place)"), lenses(GoBatchSyntaxUpdateCodeVisionProvider()))
        assertEquals(listOf("package p: What's New"), lenses(GoModernizerWhatsNewCodeVisionProvider()))
    }

    fun testNoLensesWithoutFindings() {
        myFixture.enableInspections(GoFixTestInspection())
        myFixture.configureByText("a.go", withoutFinding)
        assertEquals(emptyList<String>(), lenses(GoBatchSyntaxUpdateCodeVisionProvider()))
        assertEquals(emptyList<String>(), lenses(GoModernizerWhatsNewCodeVisionProvider()))
    }

    fun testOtherGroupsDoNotCount() {
        myFixture.enableInspections(GoOtherTestInspection())
        myFixture.configureByText("a.go", withFinding)
        assertEquals(emptyList<String>(), lenses(GoBatchSyntaxUpdateCodeVisionProvider()))
    }

    fun testLensFollowsTheEdit() {
        myFixture.enableInspections(GoFixTestInspection())
        myFixture.configureByText("a.go", withFinding)
        assertEquals(1, lenses(GoBatchSyntaxUpdateCodeVisionProvider()).size)
        myFixture.editor.document.let { doc ->
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.setText(withFinding + "\n\nfunc g(w interface{}) {}\n") }
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(listOf("package p: Update syntax (2 places)"), lenses(GoBatchSyntaxUpdateCodeVisionProvider()))
    }

    /** Regression: once the daemon has highlighted the file, the lens counts its highlights instead of running the inspections again. */
    fun testLensCountsTheDaemonHighlights() {
        myFixture.enableInspections(GoFixTestInspection())
        myFixture.configureByText("a.go", withFinding + "\n\nfunc g(w interface{}) {}\n")
        myFixture.doHighlighting()
        GoFixTestInspection.batchRuns = 0
        assertEquals(listOf("package p: Update syntax (2 places)"), lenses(GoBatchSyntaxUpdateCodeVisionProvider()))
        assertEquals(0, GoFixTestInspection.batchRuns)
    }

    fun testLensesAreGatedByDiagnostics() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.DIAGNOSTICS
        }, testRootDisposable)
        myFixture.enableInspections(GoFixTestInspection())
        myFixture.configureByText("a.go", withFinding)
        assertEquals(emptyList<String>(), lenses(GoBatchSyntaxUpdateCodeVisionProvider()))
        assertEquals(emptyList<String>(), lenses(GoModernizerWhatsNewCodeVisionProvider()))
    }

    fun testBatchText() {
        assertEquals("Update syntax (1 place)", GoSyntaxUpdateLenses.batchText(1))
        assertEquals("Update syntax (7 places)", GoSyntaxUpdateLenses.batchText(7))
        assertEquals("Update syntax (100+ places)", GoSyntaxUpdateLenses.batchText(GoSyntaxUpdate.MAX_COUNT))
    }
}
