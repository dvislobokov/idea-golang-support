package io.github.golangsupport.ide.rules

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * The user's per-project decisions about rules, on top of the defaults and the configuration source (the last word in [GoRuleSet]'s
 * merge). Only overrides are stored (`.idea/goRules.xml`): a rule nobody touched has no entry.
 */
@Service(Service.Level.PROJECT)
@State(name = "GoRuleSettings", storages = [Storage("goRules.xml")])
class GoRuleSettings(private val project: Project) : PersistentStateComponent<GoRuleSettings.State> {

    @Tag("rule")
    class RuleState {
        @get:Attribute("id") var id: String = ""
        @get:Attribute("enabled") var enabled: Boolean? = null
        @get:Attribute("level") var level: String? = null
        @get:MapAnnotation(surroundWithTag = false, entryTagName = "option", keyAttributeName = "name", valueAttributeName = "value")
        var options: MutableMap<String, String> = LinkedHashMap()

        val isEmpty: Boolean get() = enabled == null && level == null && options.isEmpty()
    }

    class State {
        @get:XCollection(style = XCollection.Style.v2)
        var rules: MutableList<RuleState> = ArrayList()
    }

    @Volatile private var state = State()
    private val tracker = SimpleModificationTracker()

    /** Bumped by every change: [GoRuleSet] drops its snapshots on it. */
    val modificationCount: Long get() = tracker.modificationCount

    override fun getState(): State = state

    override fun loadState(state: State) {
        state.rules.removeAll { it.id.isEmpty() || it.isEmpty }
        this.state = state
        tracker.incModificationCount()
    }

    /** The user's override of [id], or null. */
    fun override(id: String): GoRuleOverride? {
        val rule = state.rules.firstOrNull { it.id == id } ?: return null
        return GoRuleOverride(rule.enabled, GoRuleLevel.parse(rule.level), LinkedHashMap<String, Any?>(rule.options))
    }

    /** null: back to the configuration / the default. */
    fun setEnabled(id: String, enabled: Boolean?) = update(id) { it.enabled = enabled }

    fun setLevel(id: String, level: GoRuleLevel?) = update(id) { it.level = level?.name }

    /** An option as text (parsed by the rule's [GoRuleOption]); null removes it. */
    fun setOption(id: String, name: String, value: String?) = update(id) { if (value == null) it.options.remove(name) else it.options[name] = value }

    /** Forgets every override of [id]. */
    fun reset(id: String) = update(id) { it.enabled = null; it.level = null; it.options.clear() }

    private fun update(id: String, change: (RuleState) -> Unit) {
        synchronized(this) {
            val rules = ArrayList(state.rules)
            val rule = rules.firstOrNull { it.id == id } ?: RuleState().also { it.id = id; rules += it }
            change(rule)
            rules.removeAll { it.isEmpty }
            state = State().also { it.rules = rules }
        }
        tracker.incModificationCount()
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
    }

    companion object {
        fun getInstance(project: Project): GoRuleSettings = project.service()
    }
}
