package io.github.golangsupport.lint.config

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.ide.rules.GoRule
import io.github.golangsupport.ide.rules.GoRuleConfig
import io.github.golangsupport.ide.rules.GoRuleConfigSource
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleOverride
import io.github.golangsupport.ide.rules.GoRuleSet
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's `.golangci.yml` drives the native lint rules: the linters it enables switch their rules on, the rest off; staticcheck
 * `checks`, revive `rules`, govet / gocritic / gosec selections pick single rules; `linters.settings` become rule options. The nearest
 * config above the file wins, as golangci-lint finds it; none, or one we cannot read, leaves the rules' own defaults.
 */
class GolangciRuleConfigSource : GoRuleConfigSource {
    private class Cached(val stamp: Long, val config: GoRuleConfig?)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun configFor(project: Project, file: VirtualFile): GoRuleConfig? {
        val configFile = find(project, file) ?: return null
        val cached = cache[configFile.path]
        // the same instance while the file is unchanged: the rule set keeps one resolved snapshot per instance
        if (cached != null && cached.stamp == configFile.modificationStamp) return cached.config
        val result = GolangciConfigs.load(File(configFile.path))
        val config = result.configOrNull?.let { toRuleConfig(it, GoRuleSet.getInstance(project).allRules) }
        if (config == null) GoPluginLog.warn("lint", "${configFile.path}: ${(result as? GolangciConfigResult.Failed)?.message ?: "unsupported format"}; the rules keep their defaults")
        cache[configFile.path] = Cached(configFile.modificationStamp, config)
        return config
    }

    /** The nearest `.golangci.*` from the file's directory up to the project directory. */
    private fun find(project: Project, file: VirtualFile): VirtualFile? {
        val stop = project.basePath?.replace('\\', '/')
        var dir = file.parent
        while (dir != null) {
            for (name in GolangciConfigs.NAMES) dir.findChild(name)?.let { return it }
            if (dir.path == stop) return null
            dir = dir.parent
        }
        return null
    }

    companion object {
        /** The engine's view of [config] for [rules]: every rule gets an explicit on/off, as golangci-lint runs only what the config enables. */
        fun toRuleConfig(config: GolangciConfig, rules: List<GoRule>): GoRuleConfig {
            val linters = GolangciLinters.effectiveLinters(config)
            val revive by lazy { GolangciLinters.revive(config) }
            val overrides = HashMap<String, GoRuleOverride>()
            for (rule in rules) {
                val sub = rule.id.substringAfter(':')
                val canonical = GolangciLinters.canonical(rule.linter)
                val enabled = when {
                    rule.id.matches(STATICCHECK_ID) -> GolangciLinters.isStaticcheckEnabled(config, linters, rule.id)
                    canonical == "govet" -> "govet" in linters && GolangciLinters.isGovetAnalyzerEnabled(config, sub)
                    canonical == "revive" -> "revive" in linters && (revive.isEnabled(sub) || revive.enableAll)
                    canonical == "gocritic" -> "gocritic" in linters && GolangciLinters.isGocriticEnabled(config, sub)
                    canonical == "gosec" -> "gosec" in linters && GolangciLinters.isGosecRuleEnabled(config, sub)
                    else -> canonical in linters || rule.linterAliases.any { GolangciLinters.canonical(it) in linters }
                }
                val options: Map<String, Any?> = when (canonical) {
                    "govet" -> GolangciLinters.govetAnalyzerSettings(config, sub)
                    "gocritic" -> GolangciLinters.gocriticSettings(config, sub)
                    "revive" -> revive.rules[sub]?.let { reviveOptions(rule, it.arguments) }.orEmpty()
                    else -> emptyMap()
                }
                val level = if (canonical == "revive") GoRuleLevel.parse(revive.rules[sub]?.severity) else null
                overrides[rule.id] = GoRuleOverride(enabled, level, options)
            }
            val settings = config.settings.mapKeys { GolangciLinters.canonical(it.key) }
            return GoRuleConfig(defaultEnabled = null, linters = emptyMap(), linterOptions = settings, rules = overrides)
        }

        /** revive passes rule arguments as a list: a lone value goes to a rule's only option (`function-result-limit: [3]` -> `max`). */
        private fun reviveOptions(rule: GoRule, arguments: List<Any?>): Map<String, Any?> {
            val only = rule.options.singleOrNull() ?: return if (arguments.isEmpty()) emptyMap() else mapOf("arguments" to arguments)
            val value = arguments.singleOrNull() ?: return emptyMap()
            return mapOf(only.name to (if (value is Map<*, *>) value[only.name] ?: value.values.firstOrNull() else value))
        }

        private val STATICCHECK_ID = Regex("(SA|S|ST|QF)\\d+")
    }
}

/** A `.golangci.*` saved, created or removed: the rules of open files are resolved again. */
class GolangciConfigListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.none { it.path.substringAfterLast('/') in GolangciConfigs.NAMES }) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            GoRuleSet.getInstance(project).invalidate()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
