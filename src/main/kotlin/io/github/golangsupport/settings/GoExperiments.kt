package io.github.golangsupport.settings

import java.nio.file.Files
import java.nio.file.Path

/**
 * The GOEXPERIMENT names a toolchain knows, read from its own sources: the `bool` fields of `internal/goexperiment/flags.go` (the
 * name is the lower-cased field) and the baseline of `internal/buildcfg/exp.go` (what is on without GOEXPERIMENT). So the list follows
 * the installed Go version and nothing is hard-coded. Pure, for the tests.
 */
object GoExperiments {
    /** [platformDependent]: the baseline value is an expression (`regabiSupported`), on for most platforms. */
    data class Experiment(val name: String, val defaultOn: Boolean, val platformDependent: Boolean = false)

    private val FIELD = Regex("""^\s+([A-Z][A-Za-z0-9]*)\s+bool\b""", RegexOption.MULTILINE)
    private val BASELINE = Regex("""baseline\s*:=\s*goexperiment\.Flags\{(.*?)\n\s*}""", RegexOption.DOT_MATCHES_ALL)
    private val ENTRY = Regex("""([A-Z][A-Za-z0-9]*)\s*:\s*([^,\n]+)""")

    /** The experiments of the toolchain at [goroot]; empty when its sources are not there. */
    fun of(goroot: Path): List<Experiment> {
        val flags = runCatching { Files.readString(goroot.resolve("src/internal/goexperiment/flags.go")) }.getOrNull() ?: return emptyList()
        val exp = runCatching { Files.readString(goroot.resolve("src/internal/buildcfg/exp.go")) }.getOrNull().orEmpty()
        return parse(flags, exp)
    }

    fun parse(flagsGo: String, expGo: String): List<Experiment> {
        val baseline = BASELINE.find(expGo)?.groupValues?.get(1)?.let { body -> ENTRY.findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() } }.orEmpty()
        return FIELD.findAll(flagsGo).map { it.groupValues[1] }.distinct().map { field ->
            val value = baseline[field]
            Experiment(field.lowercase(), defaultOn = value != null && value != "false", platformDependent = value != null && value != "true" && value != "false")
        }.toList()
    }

    /** Which experiments [goexperiment] turns on and off relative to the baseline: `arenas,nogreenteagc` → (+arenas, -greenteagc). */
    fun state(experiments: List<Experiment>, goexperiment: String): Map<String, Boolean> {
        val result = experiments.associate { it.name to it.defaultOn }.toMutableMap()
        for (item in goexperiment.split(',', ' ').map(String::trim).filter(String::isNotEmpty)) {
            when {
                item == "none" -> result.keys.forEach { result[it] = false }
                item.startsWith("no") && item.removePrefix("no") in result -> result[item.removePrefix("no")] = false
                else -> result[item] = true
            }
        }
        return result
    }

    /**
     * The GOEXPERIMENT text for the chosen [enabled] set: the names that differ from the baseline, `no` before a default turned off.
     * Names the toolchain does not know but the text had ([previous]) are kept as written.
     */
    fun compose(experiments: List<Experiment>, enabled: Set<String>, previous: String = ""): String {
        val known = experiments.map { it.name }.toSet()
        val unknown = previous.split(',', ' ').map(String::trim).filter { it.isNotEmpty() && it != "none" && it.removePrefix("no") !in known && it !in known }
        val changed = experiments.mapNotNull { e ->
            val on = e.name in enabled
            when {
                on && !e.defaultOn -> e.name
                !on && e.defaultOn -> "no" + e.name
                else -> null
            }
        }
        return (changed + unknown).joinToString(",")
    }
}

/** The checklist behind Settings | Go | Build Tags → Experiments → Choose…: the experiments of the installed toolchain, the current ones checked. */
class GoExperimentsDialog private constructor(project: com.intellij.openapi.project.Project?, private val experiments: List<GoExperiments.Experiment>, private val current: String, private val goroot: String) :
    com.intellij.openapi.ui.DialogWrapper(project, true) {
    private val list = com.intellij.ui.CheckBoxList<GoExperiments.Experiment>()

    init {
        title = io.github.golangsupport.GoBundle.message("buildTags.experiments.title")
        val state = GoExperiments.state(experiments, current)
        for (e in experiments.sortedBy { it.name }) {
            val note = when {
                e.platformDependent -> "  (" + io.github.golangsupport.GoBundle.message("buildTags.experiments.platform") + ")"
                e.defaultOn -> "  (" + io.github.golangsupport.GoBundle.message("buildTags.experiments.default") + ")"
                else -> ""
            }
            list.addItem(e, e.name + note, state[e.name] == true)
        }
        init()
    }

    override fun createCenterPanel(): javax.swing.JComponent = com.intellij.ui.dsl.builder.panel {
        row { comment(io.github.golangsupport.GoBundle.message("buildTags.experiments.hint", goroot)) }
        row { scrollCell(list).align(com.intellij.ui.dsl.builder.Align.FILL) }.resizableRow()
    }.apply { preferredSize = java.awt.Dimension(420, 460) }

    fun result(): String = GoExperiments.compose(experiments, experiments.filter { list.isItemSelected(it) }.map { it.name }.toSet(), current)

    companion object {
        /** The new GOEXPERIMENT text, or null when cancelled or the toolchain sources are not found (then a message says so). */
        fun choose(project: com.intellij.openapi.project.Project?, current: String): String? {
            val goroot = io.github.golangsupport.cli.GoEnvironment.quick().goRoot
                ?: io.github.golangsupport.cli.GoCli.findExecutable()?.let { java.io.File(it).parentFile?.parentFile?.path }
            val experiments = goroot?.let { GoExperiments.of(java.nio.file.Path.of(it)) }.orEmpty()
            if (experiments.isEmpty()) {
                com.intellij.openapi.ui.Messages.showInfoMessage(project, io.github.golangsupport.GoBundle.message("buildTags.experiments.none"), io.github.golangsupport.GoBundle.message("buildTags.experiments.title"))
                return null
            }
            val dialog = GoExperimentsDialog(project, experiments, current, goroot!!)
            return if (dialog.showAndGet()) dialog.result() else null
        }
    }
}
