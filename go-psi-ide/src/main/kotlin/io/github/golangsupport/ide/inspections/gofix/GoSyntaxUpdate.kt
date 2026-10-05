package io.github.golangsupport.ide.inspections.gofix

import com.intellij.analysis.AnalysisScope
import com.intellij.analysis.BaseAnalysisAction
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile

/**
 * Refactor | Update Syntax… and the "Batch syntax update" lens: the enabled inspections of the "Go fix" group (GoLand's modernizers,
 * `groupPath="Go" groupName="Go fix"`, short names `GoFix*`) run over a scope in batch, so their findings land in Inspection Results,
 * where the quick fixes apply to one finding, to a file or to all of them at once.
 */
object GoSyntaxUpdate {
    const val GROUP_NAME = "Go fix"
    const val SHORT_NAME_PREFIX = "GoFix"
    const val PROFILE_NAME = "Update Syntax"

    /** A tool of an inspection profile as the selection sees it. */
    data class Tool(val shortName: String, val groupPath: List<String>, val enabled: Boolean)

    /** Of the "Go fix" group: by the short name (`GoFixAny`) or by the group (`Go | Go fix`), whichever the inspection has. */
    fun isGoFix(shortName: String, groupPath: List<String>): Boolean = shortName.startsWith(SHORT_NAME_PREFIX) || groupPath.lastOrNull() == GROUP_NAME

    /** The short names to run, in a stable order: the enabled Go fix tools of the profile. */
    fun select(tools: Collection<Tool>): List<String> = tools.filter { it.enabled && isGoFix(it.shortName, it.groupPath) }.map { it.shortName }.distinct().sorted()

    /** The enabled Go fix tools of [profile] (the current one of the project by default). */
    fun enabledTools(project: Project, profile: InspectionProfileImpl = currentProfile(project)): List<InspectionToolWrapper<*, *>> {
        val all = profile.getAllEnabledInspectionTools(project).map { it.tool }
        val ids = select(all.map { Tool(it.shortName, it.groupPath.toList(), true) }).toSet()
        return all.filter { it.shortName in ids }.sortedBy { it.shortName }
    }

    fun currentProfile(project: Project): InspectionProfileImpl = InspectionProjectProfileManager.getInstance(project).currentProfile

    /** A profile with [tools] only, all enabled, their settings copied from the current profile (as Run Inspection by Name builds its own). */
    fun profileOf(project: Project, tools: List<InspectionToolWrapper<*, *>>, base: InspectionProfileImpl = currentProfile(project)): InspectionProfileImpl {
        val copies: List<InspectionToolWrapper<*, *>> = tools.map { InspectionProfileImpl.copyToolSettings(it) }
        return InspectionProfileImpl(PROFILE_NAME, InspectionToolsSupplier.Simple(copies), base).also { profile ->
            copies.forEach { profile.enableTool(it.shortName, project) }
        }
    }

    /** Runs the enabled Go fix inspections over [scope]; the findings open in Inspection Results. On the EDT. */
    fun run(project: Project, scope: AnalysisScope) {
        val tools = enabledTools(project)
        if (tools.isEmpty()) {
            Messages.showInfoMessage(project, "No inspection of the Go fix group is enabled in the current profile (Settings | Editor | Inspections | Go | Go fix).", PROFILE_NAME)
            return
        }
        val context = (InspectionManager.getInstance(project) as InspectionManagerEx).createNewGlobalContext()
        context.setExternalProfile(profileOf(project, tools))
        context.setCurrentScope(scope)
        context.doInspections(scope)
    }

    /**
     * How many Go fix findings [file] has: the enabled local Go fix tools of the profile for this file run over it directly (the
     * Go fix inspections are cheap PSI visitors; the daemon's highlights would be the cheaper source, but code vision is computed in the
     * daemon too, in no fixed order with the inspection pass, so the lens would come and go). Stops counting at [limit].
     */
    fun countFindings(file: PsiFile, limit: Int = MAX_COUNT): Int {
        val project = file.project
        val profile = currentProfile(project)
        val manager = InspectionManager.getInstance(project)
        var count = 0
        for (tools in profile.getAllEnabledInspectionTools(project)) {
            if (!isGoFix(tools.shortName, tools.tool.groupPath.toList())) continue
            val wrapper = tools.getEnabledTool(file) as? LocalInspectionToolWrapper ?: continue
            if (!wrapper.isApplicable(file.language)) continue
            ProgressManager.checkCanceled()
            count += (wrapper.tool as LocalInspectionTool).processFile(file, manager).size
            if (count >= limit) return limit
        }
        return count
    }

    const val MAX_COUNT = 100
}

/**
 * Refactor | Update Syntax… (GoLand's `GoSyntaxUpdateAction`): the scope dialog of the platform (file, directory, module, project,
 * custom scope), then the enabled Go fix inspections over it, results with their batch fixes in Inspection Results. GoLand shows its
 * own preview of the rewritten code; here the preview is the tool window's (each finding with its fix, Apply to all).
 */
class GoUpdateSyntaxAction : BaseAnalysisAction("Update Syntax", "Update Syntax") {
    override fun analyze(project: Project, scope: AnalysisScope) = GoSyntaxUpdate.run(project, scope)
}
