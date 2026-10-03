package io.github.golangsupport.settings

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.modifyAndCommitProjectProfile
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoDiagnosticsInspectionBase
import io.github.golangsupport.ide.inspections.GoPrintfInspection
import io.github.golangsupport.ide.rules.GoRule
import io.github.golangsupport.ide.rules.GoRuleConfig
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleOverride
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.GoRules

/**
 * Where the Built-in table of Settings | Go | Linters reads from and writes to: the inspections of the plugin live in the project's
 * inspection profile (so the table and Settings | Editor | Inspections show the same), the rules of the engine in [GoRuleSettings]
 * (`.idea/goRules.xml`) over `.golangci.yml`.
 */
object GoChecksStore {
    const val PLUGIN_PACKAGE = "io.github.golangsupport."

    /** The group of the Go lint rules inspection and the prefix of the rule groups (`Lint rules · staticcheck`). */
    const val LINT_RULES = "Lint rules"

    // the sub-areas of go-psi-ide's inspections, by the package of the implementation: the registrations all say `Go`
    private val AREAS = mapOf("flow" to "Data flow", "lint" to "Lint", "project" to "Project")

    fun load(project: Project): GoChecksModel {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val checks = ArrayList<GoCheck>()
        val states = HashMap<String, GoCheckState>()
        for (wrapper in inspections(profile)) {
            val tools = profile.getToolsOrNull(wrapper.shortName, project)
            val base = level(wrapper.defaultLevel)
            val current = level(tools?.level ?: wrapper.defaultLevel)
            checks += GoCheck(
                GoCheckKind.INSPECTION, wrapper.shortName, wrapper.displayName, groupOf(wrapper), wrapper.isEnabledByDefault, base,
                goplsQuiet = isGoplsQuiet(wrapper), description = lazyText { wrapper.loadDescription().orEmpty() },
            )
            states[wrapper.shortName] = GoCheckState(tools?.isEnabled ?: wrapper.isEnabledByDefault, if (current == base) GoCheckLevel.DEFAULT else current)
        }
        val ruleSet = GoRuleSet.getInstance(project)
        val config = representativeFile(project)?.let { ruleSet.configFor(it) }
        val settings = GoRuleSettings.getInstance(project)
        val master = checks.firstOrNull { it.id == GoRules.SHORT_NAME }?.id
        for (rule in ruleSet.allRules) {
            val check = ruleCheck(rule, config, master)
            checks += check
            val user = settings.override(rule.id)
            states[rule.id] = GoCheckState(
                enabled = user?.enabled ?: check.baseEnabled,
                level = user?.level?.let(::level) ?: GoCheckLevel.DEFAULT,
                options = user?.options.orEmpty().mapValues { GoCheckOption.text(it.value) },
            )
        }
        return GoChecksModel(checks, states)
    }

    /** A rule as a row: what `.golangci.yml` (or the rule's default) gives without the user's layer is its base. */
    fun ruleCheck(rule: GoRule, config: GoRuleConfig?, master: String?): GoCheck {
        val effective = config ?: GoRuleConfig.EMPTY
        val baseEnabled = GoRuleSet.resolve(rule, effective, null) != null
        // forced on to read the level and the options the configuration gives, whatever it says about on/off
        val base = GoRuleSet.resolve(rule, effective, GoRuleOverride(enabled = true))
        val configured = config != null && (config.rules.containsKey(rule.id) || config.linters.containsKey(rule.linter) || config.defaultEnabled != null)
        return GoCheck(
            GoCheckKind.RULE, rule.id, rule.title, "$LINT_RULES · ${rule.linter}", baseEnabled, base?.level?.let(::level) ?: level(rule.defaultLevel),
            options = rule.options.map { GoCheckOption.of(it.name, it.default, it.description, base?.options?.raw?.get(it.name)) },
            configured = configured, goplsQuiet = rule.overlapsGopls, master = master, description = lazyText { rule.description },
        )
    }

    /**
     * Writes what changed: inspections to the project profile (one commit), rules to [GoRuleSettings] (only what differs from the base is
     * kept there). Then the rule snapshots are dropped and the highlighting restarts.
     */
    fun save(project: Project, model: GoChecksModel, inspections: (InspectionProfileWriter) -> Unit = { modifyAndCommitProjectProfile(project, it::write) }) {
        val changed = model.changed()
        val changedInspections = changed.filter { it.kind == GoCheckKind.INSPECTION }
        if (changedInspections.isNotEmpty()) inspections(InspectionProfileWriter(project, model, changedInspections))
        val settings = GoRuleSettings.getInstance(project)
        for (check in changed.filter { it.kind == GoCheckKind.RULE }) {
            val wanted = GoChecksModel.ruleOverride(check, model.state(check.id))
            settings.reset(check.id)
            wanted.enabled?.let { settings.setEnabled(check.id, it) }
            wanted.level?.let { settings.setLevel(check.id, ruleLevel(it)) }
            wanted.options.forEach { (name, text) -> settings.setOption(check.id, name, text) }
        }
        GoRuleSet.getInstance(project).invalidate()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    /** Writes the changed inspection rows into a profile: the modifiable model of the project profile, or (tests) a profile at hand. */
    class InspectionProfileWriter(private val project: Project, private val model: GoChecksModel, private val checks: List<GoCheck>) {
        fun write(profile: InspectionProfileImpl) {
            for (check in checks) {
                // a profile without the tool (one made for tests) has nothing to switch
                if (profile.getToolsOrNull(check.id, project) == null) continue
                val state = model.state(check.id)
                val before = model.initial.getValue(check.id)
                if (state.enabled != before.enabled) profile.setToolEnabled(check.id, state.enabled, project)
                if (state.level == before.level) continue
                val key = HighlightDisplayKey.find(check.id) ?: continue
                val level = if (state.level != GoCheckLevel.DEFAULT) displayLevel(state.level)
                else profile.getInspectionTool(check.id, project)?.defaultLevel ?: displayLevel(check.baseLevel)
                profile.setErrorLevel(key, level, project)
            }
        }
    }

    /** The inspections of the plugin (Go, go.mod, the project checks, the Go lint rules inspection), by their implementation class: no instance made. */
    fun inspections(profile: InspectionProfileImpl): List<InspectionToolWrapper<*, *>> {
        val inProfile = profile.getInspectionTools(null).filter { it.extension?.implementationClass?.startsWith(PLUGIN_PACKAGE) == true }
        val known = inProfile.mapTo(HashSet()) { it.shortName }
        // a profile may lack some (one made for tests has only what they enable): their registrations give the rest
        val missing = LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .filter { it.implementationClass?.startsWith(PLUGIN_PACKAGE) == true && it.getShortName() !in known }.map { LocalInspectionToolWrapper(it) }
        return inProfile + missing
    }

    /** `Go`, `Go · Data flow`, `Go modules`…, and `Lint rules` for the inspection that runs the rules. */
    fun groupOf(wrapper: InspectionToolWrapper<*, *>): String {
        if (wrapper.shortName == GoRules.SHORT_NAME) return LINT_RULES
        val base = (wrapper.groupPath.toList().takeIf { it.isNotEmpty() } ?: listOf(wrapper.groupDisplayName)).joinToString(" · ")
        val implementation = wrapper.extension?.implementationClass.orEmpty()
        val area = implementation.substringAfter(".inspections.", "").substringBeforeLast('.', "").substringBefore('.')
        return AREAS[area]?.let { "$base · $it" } ?: base
    }

    /** What is quiet while gopls serves the diagnostics: the analysis inspections of go-psi-ide check the switch themselves. */
    private fun isGoplsQuiet(wrapper: InspectionToolWrapper<*, *>): Boolean {
        if (wrapper.extension?.implementationClass?.startsWith("io.github.golangsupport.ide.") != true) return false
        val tool = runCatching { wrapper.tool }.getOrNull() ?: return false
        return tool is GoAnalysisInspectionBase || tool is GoDiagnosticsInspectionBase || tool is GoPrintfInspection
    }

    /** A file in the project directory: `.golangci.yml` is looked for from its directory up, as for a file of the root package. */
    private fun representativeFile(project: Project): VirtualFile? {
        val dir = project.guessProjectDir() ?: return null
        return dir.findChild("go.mod") ?: dir.children.firstOrNull { !it.isDirectory }
    }

    fun level(level: HighlightDisplayLevel): GoCheckLevel = level(level.severity)

    fun level(severity: HighlightSeverity): GoCheckLevel = when {
        severity >= HighlightSeverity.ERROR -> GoCheckLevel.ERROR
        severity >= HighlightSeverity.WARNING -> GoCheckLevel.WARNING
        severity >= HighlightSeverity.WEAK_WARNING -> GoCheckLevel.WEAK_WARNING
        else -> GoCheckLevel.INFO
    }

    fun level(level: GoRuleLevel): GoCheckLevel = when (level) {
        GoRuleLevel.ERROR -> GoCheckLevel.ERROR
        GoRuleLevel.WARNING -> GoCheckLevel.WARNING
        GoRuleLevel.WEAK_WARNING -> GoCheckLevel.WEAK_WARNING
        GoRuleLevel.INFO -> GoCheckLevel.INFO
    }

    /** INFO is "no highlighting, fix only", as [GoRuleLevel.INFO]; DEFAULT has no level of its own and is resolved by the caller. */
    fun displayLevel(level: GoCheckLevel): HighlightDisplayLevel = when (level) {
        GoCheckLevel.ERROR -> HighlightDisplayLevel.ERROR
        GoCheckLevel.WARNING, GoCheckLevel.DEFAULT -> HighlightDisplayLevel.WARNING
        GoCheckLevel.WEAK_WARNING -> HighlightDisplayLevel.WEAK_WARNING
        GoCheckLevel.INFO -> HighlightDisplayLevel.DO_NOT_SHOW
    }

    fun ruleLevel(level: GoCheckLevel): GoRuleLevel? = when (level) {
        GoCheckLevel.DEFAULT -> null
        GoCheckLevel.ERROR -> GoRuleLevel.ERROR
        GoCheckLevel.WARNING -> GoRuleLevel.WARNING
        GoCheckLevel.WEAK_WARNING -> GoRuleLevel.WEAK_WARNING
        GoCheckLevel.INFO -> GoRuleLevel.INFO
    }

    private fun lazyText(text: () -> String): () -> String {
        val value by lazy { runCatching(text).getOrDefault("") }
        return { value }
    }
}
