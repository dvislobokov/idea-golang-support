package io.github.golangsupport.ide.rules

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.lang.psi.GoFile
import java.util.Collections
import java.util.WeakHashMap

/** What a configuration says about one rule; null fields leave the decision to the layer below. */
class GoRuleOverride(val enabled: Boolean? = null, val level: GoRuleLevel? = null, val options: Map<String, Any?> = emptyMap()) {
    val isEmpty: Boolean get() = enabled == null && level == null && options.isEmpty()
}

/**
 * A rule configuration (what a `.golangci.yml` boils down to). Resolution per rule, most specific first:
 * enabled = [rules]`[id]` ?: [linters]`[linter]` (true: the rule's [GoRule.enabledWithLinter]) ?: [defaultEnabled] (same) ?: [GoRule.enabledByDefault];
 * level = [rules]`[id]` ?: [GoRule.defaultLevel]; options = rule defaults < [linterOptions]`[linter]` < [rules]`[id]`.options.
 */
class GoRuleConfig(
    /** golangci-lint `linters.default`: true for `all`, false for `none`, null for `standard` (the rules' own defaults). */
    val defaultEnabled: Boolean? = null,
    /** Linter name -> on/off (`linters.enable` / `linters.disable`). */
    val linters: Map<String, Boolean> = emptyMap(),
    /** Linter name -> settings shared by its rules (`linters.settings.<linter>`). */
    val linterOptions: Map<String, Map<String, Any?>> = emptyMap(),
    /** Rule id -> override (staticcheck `checks`, revive `rules`, per-rule severities). */
    val rules: Map<String, GoRuleOverride> = emptyMap(),
) {
    companion object {
        val EMPTY: GoRuleConfig = GoRuleConfig()
    }
}

/**
 * Where a rule configuration comes from besides the user's settings: the `.golangci.yml` reader of the host implements it.
 * Asked on every highlighting pass of every file: answer from a cache, return the same instance while the configuration has not
 * changed (the rule set keeps one resolved snapshot per instance), and call `DaemonCodeAnalyzer.restart()` when it changes.
 * The first source that answers non-null wins.
 */
interface GoRuleConfigSource {
    /** The configuration that applies to [file] (say, the nearest `.golangci.yml` above it), or null. */
    fun configFor(project: Project, file: VirtualFile): GoRuleConfig?

    companion object {
        val EP_NAME: ExtensionPointName<GoRuleConfigSource> = ExtensionPointName.create("io.github.golangsupport.goRuleConfigSource")
    }
}

/** The default source: no configuration, the rules' own defaults (and the user's settings) decide. */
class GoNoRuleConfigSource : GoRuleConfigSource {
    override fun configFor(project: Project, file: VirtualFile): GoRuleConfig? = null
}

/** A rule as it runs: its effective level and options. */
class GoActiveRule(val rule: GoRule, val level: GoRuleLevel, val options: GoRuleOptions)

/**
 * The enabled rules of one configuration, grouped by scope, with the variants a pass needs precomputed lazily (dumb mode: syntax
 * rules only; gopls owns diagnostics: rules that overlap gopls left out), so a pass filters nothing and a disabled rule costs nothing.
 */
class GoRuleSnapshot internal constructor(val rules: List<GoActiveRule>) {
    private val variants = arrayOfNulls<Array<Array<GoActiveRule>>>(4)

    /** The subscribers per [GoRuleScope] ordinal for a pass. */
    internal fun forRun(dumb: Boolean, goplsDiagnostics: Boolean): Array<Array<GoActiveRule>> {
        val index = (if (dumb) 1 else 0) + (if (goplsDiagnostics) 2 else 0)
        variants[index]?.let { return it }
        val kept = rules.filter { (!dumb || it.rule.needs.all { n -> n == GoRuleNeed.SYNTAX }) && (!goplsDiagnostics || !it.rule.overlapsGopls) }
        val byScope = Array(GoRuleScope.entries.size) { scope -> kept.filter { it.rule.scope.ordinal == scope }.toTypedArray() }
        variants[index] = byScope
        return byScope
    }

    fun find(id: String): GoActiveRule? = rules.firstOrNull { it.rule.id == id }
}

/**
 * The registered rules and the enabled set per file. Merge order of the decisions: rule defaults < [GoRuleConfigSource] < the user's
 * [GoRuleSettings]. Snapshots are kept per configuration instance and dropped when the settings or the registered rules change.
 */
@Service(Service.Level.PROJECT)
class GoRuleSet(private val project: Project) : Disposable {

    private val snapshots: MutableMap<Any, GoRuleSnapshot> = Collections.synchronizedMap(WeakHashMap())
    @Volatile private var settingsStamp = -1L
    private val noConfig = Any()

    init {
        GoRule.EP_NAME.addChangeListener({ invalidate() }, this)
        GoRuleConfigSource.EP_NAME.addChangeListener({ invalidate() }, this)
    }

    /** Every registered rule, enabled or not. */
    val allRules: List<GoRule> get() = GoRule.EP_NAME.extensionList

    fun rule(id: String): GoRule? = allRules.firstOrNull { it.id == id }

    fun rulesFor(scope: GoRuleScope): List<GoRule> = allRules.filter { it.scope == scope }

    /** The configuration that applies to [file], or null. */
    fun configFor(file: VirtualFile): GoRuleConfig? = GoRuleConfigSource.EP_NAME.extensionList.firstNotNullOfOrNull { it.configFor(project, file) }

    /** The enabled rules for [file] (a Go file of this project). */
    fun snapshot(file: PsiFile): GoRuleSnapshot {
        val config = (file as? GoFile)?.let { configFor(GoPsiUtil.originalVirtualFile(it)) }
        val stamp = GoRuleSettings.getInstance(project).modificationCount
        if (stamp != settingsStamp) {
            snapshots.clear()
            settingsStamp = stamp
        }
        return snapshots.getOrPut(config ?: noConfig) { compute(config) }
    }

    /** Drops the snapshots (rules registered or removed, a configuration source changed). */
    fun invalidate() {
        snapshots.clear()
    }

    private fun compute(config: GoRuleConfig?): GoRuleSnapshot {
        val settings = GoRuleSettings.getInstance(project)
        return GoRuleSnapshot(allRules.mapNotNull { resolve(it, config ?: GoRuleConfig.EMPTY, settings.override(it.id)) })
    }

    /** Whether [file] gets [id] from the [GoRules] inspection in this pass: the inspection is on for it, it is analysed and the rule runs. */
    fun runs(file: PsiFile, id: String): Boolean {
        if (!GoAnalysisScope.isAnalysed(file)) return false
        val key = HighlightDisplayKey.find(GoRules.SHORT_NAME) ?: return false
        if (!InspectionProjectProfileManager.getInstance(project).currentProfile.isToolEnabled(key, file)) return false
        val active = snapshot(file).find(id) ?: return false
        if (DumbService.isDumb(project) && active.rule.needs.any { it != GoRuleNeed.SYNTAX }) return false
        return !active.rule.overlapsGopls || GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, project)
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): GoRuleSet = project.service()

        /** One rule through the three layers; null when it ends up disabled. */
        fun resolve(rule: GoRule, config: GoRuleConfig, user: GoRuleOverride?): GoActiveRule? {
            val configured = config.rules[rule.id]
            val enabled = user?.enabled ?: configured?.enabled
                ?: config.linters[rule.linter]?.let { it && rule.enabledWithLinter }
                ?: config.defaultEnabled?.let { it && rule.enabledWithLinter }
                ?: rule.enabledByDefault
            if (!enabled) return null
            val level = user?.level ?: configured?.level ?: rule.defaultLevel
            val raw = LinkedHashMap<String, Any?>()
            for (option in rule.options) raw[option.name] = option.default
            config.linterOptions[rule.linter]?.let(raw::putAll)
            configured?.options?.let(raw::putAll)
            user?.options?.let(raw::putAll)
            return GoActiveRule(rule, level, GoRuleOptions(raw))
        }
    }
}
