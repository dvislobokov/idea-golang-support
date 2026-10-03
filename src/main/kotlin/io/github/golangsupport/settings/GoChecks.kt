package io.github.golangsupport.settings

import io.github.golangsupport.GoBundle

/** The level column of Settings | Go | Linters: an inspection's severity or a rule's level; DEFAULT leaves it to the registration / `.golangci.yml`. */
enum class GoCheckLevel(val title: String) {
    DEFAULT("Default"),
    ERROR("Error"),
    WARNING("Warning"),
    WEAK_WARNING("Weak warning"),
    INFO("Info");

    val label: String get() = GoBundle.messageOr("checks.level.$name", title)
}

enum class GoCheckKind { INSPECTION, RULE }

/** An option of a rule as the page edits it: text, parsed by the rule when it runs. */
data class GoCheckOption(val name: String, val type: Type, val description: String, val baseText: String) {
    enum class Type { BOOL, INT, STRING, LIST }

    /** Null when [text] is a value of [type]; else what is wrong, for the apply error. */
    fun problem(text: String): String? = when {
        text.isBlank() -> null
        type == Type.INT && text.trim().toIntOrNull() == null -> "$name: '$text' is not a number"
        type == Type.BOOL && text.trim().lowercase().toBooleanStrictOrNull() == null -> "$name: '$text' is not true or false"
        else -> null
    }

    companion object {
        /** The type of an option from its default: the rule engine's options are typed by them. */
        fun of(name: String, default: Any, description: String, base: Any?): GoCheckOption {
            val type = when (default) {
                is Boolean -> Type.BOOL
                is Int, is Long -> Type.INT
                is Collection<*> -> Type.LIST
                else -> Type.STRING
            }
            return GoCheckOption(name, type, description, text(base ?: default))
        }

        fun text(value: Any?): String = when (value) {
            null -> ""
            is Collection<*> -> value.joinToString(", ") { it.toString() }
            else -> value.toString()
        }
    }
}

/**
 * One row of the Built-in table: an inspection of the plugin (Go, go.mod) or a rule of the rule engine.
 *
 * @property group the row's group: the inspection's group (with the sub-area of its package) or `Lint rules · <linter>`.
 * @property baseEnabled what holds without the user: the registration, or `.golangci.yml` / the rule's default.
 * @property baseLevel the level DEFAULT stands for.
 * @property configured `.golangci.yml` decides the rule (what "set by .golangci.yml" says).
 * @property goplsQuiet quiet while gopls serves the diagnostics (Language features: gopls).
 * @property master the id of the row that switches this one off with it (the Go lint rules inspection for every rule).
 */
data class GoCheck(
    val kind: GoCheckKind,
    val id: String,
    val name: String,
    val group: String,
    val baseEnabled: Boolean,
    val baseLevel: GoCheckLevel,
    val options: List<GoCheckOption> = emptyList(),
    val configured: Boolean = false,
    val goplsQuiet: Boolean = false,
    val master: String? = null,
    val description: () -> String = { "" },
)

/** What the user has for a row: on/off, level, rule options as text (an option not in the map keeps its base value). */
data class GoCheckState(val enabled: Boolean, val level: GoCheckLevel = GoCheckLevel.DEFAULT, val options: Map<String, String> = emptyMap())

/** The user's layer of a rule as [io.github.golangsupport.ide.rules.GoRuleSettings] keeps it: only what differs from the base. */
data class GoRuleOverrideText(val enabled: Boolean?, val level: GoCheckLevel?, val options: Map<String, String>)

/** A row of the table: a group header or a check. */
sealed interface GoChecksRow {
    data class Header(val group: String) : GoChecksRow
    data class Item(val check: GoCheck) : GoChecksRow
}

/**
 * The Built-in table of Settings | Go | Linters without Swing: the checks, their edited states over the loaded ones, the filter and the
 * group actions. Pure, for the tests; reading from and writing to the profile and the rule settings is [GoChecksStore].
 */
class GoChecksModel(checks: List<GoCheck>, initial: Map<String, GoCheckState>) {
    // a sub-group right after its group: `Go`, `Go · Data flow`, then `Go modules`
    val checks: List<GoCheck> = checks.sortedWith(compareBy<GoCheck>({ it.group.replace(" · ", " \u0000") }, { it.master != null || it.kind == GoCheckKind.RULE }, { it.name.lowercase() }))
    private val byId = this.checks.associateBy { it.id }
    val initial: Map<String, GoCheckState> = this.checks.associate { it.id to (initial[it.id] ?: GoCheckState(it.baseEnabled)) }
    private val states = LinkedHashMap(this.initial)

    var filter: String = ""
        set(value) { field = value.trim(); rows = computeRows() }

    /** The visible rows: groups sorted, a header before each, the checks that match [filter]. */
    var rows: List<GoChecksRow> = computeRows()
        private set

    val groups: List<String> get() = checks.map { it.group }.distinct()

    fun check(id: String): GoCheck? = byId[id]

    fun state(id: String): GoCheckState = states.getValue(id)

    fun setState(id: String, state: GoCheckState) { states[id] = state }

    fun setEnabled(id: String, enabled: Boolean) = setState(id, state(id).copy(enabled = enabled))

    fun setLevel(id: String, level: GoCheckLevel) = setState(id, state(id).copy(level = level))

    /** [text] blank or equal to the base: back to the base value. */
    fun setOption(id: String, name: String, text: String) {
        val option = byId[id]?.options?.firstOrNull { it.name == name } ?: return
        val options = state(id).options.toMutableMap()
        if (text.isBlank() || text.trim() == option.baseText) options.remove(name) else options[name] = text.trim()
        setState(id, state(id).copy(options = options))
    }

    /** The option's text as the page shows it: the user's, else the base. */
    fun optionText(id: String, name: String): String = state(id).options[name] ?: byId[id]?.options?.firstOrNull { it.name == name }?.baseText.orEmpty()

    fun inGroup(group: String): List<GoCheck> = checks.filter { it.group == group }

    /** The checkbox of a header: on when every check of the group is. */
    fun groupEnabled(group: String): Boolean = inGroup(group).all { state(it.id).enabled }

    fun setGroupEnabled(group: String, enabled: Boolean) = inGroup(group).forEach { setEnabled(it.id, enabled) }

    /** Back to the base: the registration or `.golangci.yml` decides again, level Default, options of the base. */
    fun resetGroup(group: String) = inGroup(group).forEach { reset(it.id) }

    fun resetAll() = checks.forEach { reset(it.id) }

    fun reset(id: String) { byId[id]?.let { states[id] = GoCheckState(it.baseEnabled) } }

    /** Off because its [GoCheck.master] (the Go lint rules inspection) is off. */
    fun mutedByMaster(check: GoCheck): Boolean = check.master?.let { states[it]?.enabled == false } == true

    val isModified: Boolean get() = states != initial

    /** The rows whose state differs from the loaded one: what apply writes. */
    fun changed(): List<GoCheck> = checks.filter { states[it.id] != initial[it.id] }

    /** The first malformed option text, for the apply error. */
    fun problem(): String? = checks.firstNotNullOfOrNull { check -> check.options.firstNotNullOfOrNull { o -> state(check.id).options[o.name]?.let(o::problem) } }

    private fun computeRows(): List<GoChecksRow> {
        val needle = filter.lowercase()
        val result = ArrayList<GoChecksRow>()
        for (group in groups) {
            val matching = inGroup(group).filter { needle.isEmpty() || matches(it, needle) || group.lowercase().contains(needle) }
            if (matching.isEmpty()) continue
            result += GoChecksRow.Header(group)
            matching.mapTo(result) { GoChecksRow.Item(it) }
        }
        return result
    }

    private fun matches(check: GoCheck, needle: String): Boolean =
        check.name.lowercase().contains(needle) || check.id.lowercase().contains(needle) || runCatching { check.description() }.getOrDefault("").lowercase().contains(needle)

    companion object {
        /** A rule's state as the override to store: what equals the base is not stored (the configuration decides it again). */
        fun ruleOverride(check: GoCheck, state: GoCheckState): GoRuleOverrideText = GoRuleOverrideText(
            enabled = state.enabled.takeIf { it != check.baseEnabled },
            level = state.level.takeIf { it != GoCheckLevel.DEFAULT },
            options = state.options.filter { (name, text) -> check.options.any { it.name == name && it.baseText != text } },
        )
    }
}
