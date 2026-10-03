package io.github.golangsupport.settings

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.GoBundle
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Settings | Go | Language Server | gopls: the settings of the installed gopls as a form. The form is made of the catalogue of the server
 * ([GoplsCatalogue]): its groups, a control per type of setting, its words as the descriptions. A control shows the value in effect
 * (the default of gopls, or what the plugin sets by itself); only what is changed away from that is stored, and sent on top of it.
 * [Configurable.NoScroll]: the form scrolls under the search field, and the Settings dialog must not scroll the two together.
 */
class GoplsSettingsConfigurable(private val project: Project) : Configurable, Configurable.NoScroll {
    private val settings get() = GoSettings.getInstance()

    /** Setting -> the JSON text of its override: the state of the page. */
    private val edited = LinkedHashMap<String, String>()

    /** What is typed in a field and is not a value of its type: kept to refuse Apply with a reason. */
    private val invalid = LinkedHashMap<String, String>()
    private var options: List<GoplsOption> = emptyList()

    /** What the plugin sets by itself, as JSON texts. */
    private var pluginDefaults: Map<String, String> = emptyMap()

    private val status = JBLabel(GoBundle.message("gopls.asking")).apply { foreground = UIUtil.getContextHelpForeground() }
    private val filter = SearchTextField(false)
    private val content = JPanel(BorderLayout())

    override fun getDisplayName(): String = "gopls"

    override fun createComponent(): JComponent {
        filter.textEditor.emptyText.text = GoBundle.message("gopls.search")
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })
        val reset = ActionLink(GoBundle.message("gopls.reset")) {
            edited.clear()
            invalid.clear()
            rebuild()
        }
        val top = JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(4))).apply {
            add(filter, BorderLayout.CENTER)
            add(reset, BorderLayout.EAST)
            add(status, BorderLayout.SOUTH)
        }
        load()
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(top, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(content, true), BorderLayout.CENTER)
        }
    }

    /** The catalogue is a run of gopls: off EDT, the page fills in when it is there. */
    private fun load() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = GoplsCatalogue.load()
            ApplicationManager.getApplication().invokeLater({
                options = loaded?.options.orEmpty()
                status.text = if (loaded == null) "gopls is not found, or does not answer `gopls api-json`: see Settings | Go | Tools"
                else "gopls ${loaded.version}, ${options.size} settings. The controls show what is in effect; the server is restarted on Apply"
                reset()
            }, ModalityState.any())
        }
    }

    // --- values ---

    /** The value a setting has without an override of this page. */
    private fun inEffect(option: GoplsOption): String = pluginDefaults[option.name] ?: option.default
    private fun value(option: GoplsOption): String = edited[option.name] ?: inEffect(option)

    private fun set(option: GoplsOption, json: String?) {
        invalid.remove(option.name)
        if (json == null || json == inEffect(option)) edited.remove(option.name) else edited[option.name] = json
    }

    /** The keys of a map of flags with what each is without an override: the defaults of the keys, then what the plugin sets. */
    private fun flagsInEffect(option: GoplsOption): Map<String, Boolean> =
        option.enumKeys.associate { it.name to (it.default == "true") } + GoplsValues.jsonToFlags(pluginDefaults[option.name])

    private fun flags(option: GoplsOption): Map<String, Boolean> = flagsInEffect(option) + GoplsValues.jsonToFlags(edited[option.name])

    private fun setFlag(option: GoplsOption, key: String, value: Boolean) = setFlags(option, flags(option) + (key to value))

    private fun setFlags(option: GoplsOption, ticked: Map<String, Boolean>) {
        val json = GoplsValues.flagsToJson(ticked, flagsInEffect(option))
        if (json == null) edited.remove(option.name) else edited[option.name] = json
    }

    // --- the form ---

    private fun rebuild() {
        content.removeAll()
        if (options.isNotEmpty()) content.add(form(filter.text.trim()), BorderLayout.NORTH)
        content.revalidate()
        content.repaint()
    }

    private fun matches(option: GoplsOption, text: String): Boolean = text.isEmpty() || option.name.contains(text, ignoreCase = true) ||
        GoplsDocs.title(option.name).contains(text, ignoreCase = true) || option.doc.contains(text, ignoreCase = true) ||
        option.enumKeys.any { it.name.contains(text, ignoreCase = true) }

    private fun form(text: String): DialogPanel = panel {
        for ((hierarchy, all) in options.groupBy { it.hierarchy }) {
            val visible = all.filter { matches(it, text) }
            if (visible.isEmpty()) continue
            group(GoplsDocs.groupTitle(hierarchy)) {
                val (regular, special) = visible.partition { it.status.isEmpty() }
                regular.forEach { option(it, text) }
                if (special.isNotEmpty()) {
                    // what gopls itself calls experimental, advanced or for debugging: out of the way until looked for or changed
                    collapsibleGroup(GoBundle.message("gopls.advanced")) { special.forEach { option(it, text) } }
                        .apply { expanded = text.isNotEmpty() || special.any { it.name in edited } }
                }
            }
        }
    }

    private fun Panel.option(option: GoplsOption, filterText: String) {
        val title = GoplsDocs.title(option.name)
        val note = listOf(option.status, if (option.deprecation.isEmpty()) "" else "deprecated: " + option.deprecation).filter { it.isNotEmpty() }.joinToString("; ")
        val summary = GoplsDocs.summary(option.name, option.doc) + if (note.isEmpty()) "" else " <i>($note)</i>"
        val help = "<b>${option.name}</b> (${option.type})<br><br>" + GoplsDocs.html(option.doc)
        when {
            option.isBool -> row {
                checkBox(title).comment(summary).applyToComponent {
                    isSelected = value(option) == "true"
                    addActionListener { set(option, isSelected.toString()) }
                }
                contextHelp(help)
            }
            option.isEnum -> row("$title:") {
                comboBox(option.choices).comment(summary).applyToComponent {
                    selectedItem = GoplsCatalogue.display(value(option), plain = true)
                    addActionListener { set(option, GoplsCatalogue.toJson(option, selectedItem?.toString().orEmpty())) }
                }
                contextHelp(help + values(option))
            }
            option.type.startsWith("map[") && option.type.endsWith("bool") && option.enumKeys.isNotEmpty() -> flagsOption(option, title, summary, help, filterText)
            else -> row("$title:") {
                val list = option.type.startsWith("[]")
                val map = option.type.startsWith("map[")
                val field = if (list || map) ExpandableTextField() else com.intellij.ui.components.JBTextField()
                // empty is "as it is without this page", and the field says what that is
                (field as? com.intellij.ui.components.JBTextField)?.emptyText?.text = display(option, inEffect(option)).ifEmpty { "empty" }
                field.text = edited[option.name]?.let { display(option, it) }.orEmpty()
                onChange(field) { typed(option, field.text) }
                if (list || map) cell(field).columns(COLUMNS_LARGE).comment(summary) else cell(field).columns(COLUMNS_MEDIUM).comment(summary)
                contextHelp(
                    help + when {
                        list -> "<br><br>Items are separated by spaces; quotes keep an item with spaces together."
                        map -> "<br><br><code>NAME=value</code> pairs separated by spaces."
                        else -> ""
                    },
                )
            }
        }
    }

    /** A map of flags: ticks when they fit on the page, a dialog with a search when there are hundreds (the analyzers). */
    private fun Panel.flagsOption(option: GoplsOption, title: String, summary: String, help: String, filterText: String) {
        if (option.enumKeys.size > INLINE_FLAGS) {
            row("$title:") {
                val changed = JBLabel(changedText(option))
                button("Configure...") {
                    val dialog = FlagsDialog(project, title, option, flags(option))
                    if (dialog.showAndGet()) {
                        setFlags(option, dialog.ticked())
                        changed.text = changedText(option)
                    }
                }
                cell(changed).comment(summary)
                contextHelp(help)
            }
            return
        }
        row {
            label(title).comment(summary)
            contextHelp(help)
        }
        indent {
            val ticks = flags(option)
            // a search that names keys shows those keys; one that names the setting shows all of them
            val named = option.enumKeys.filter { filterText.isNotEmpty() && it.name.contains(filterText, ignoreCase = true) }
            for (key in named.ifEmpty { option.enumKeys }) row {
                checkBox(key.name).comment(GoplsDocs.summary(key.name, key.doc.ifEmpty { key.name })).applyToComponent {
                    isSelected = ticks[key.name] == true
                    addActionListener { setFlag(option, key.name, isSelected) }
                }
            }
        }
    }

    private fun changedText(option: GoplsOption): String {
        val ticks = flags(option)
        val changed = GoplsValues.jsonToFlags(edited[option.name]).size
        return "${ticks.count { it.value }} of ${option.enumKeys.size} enabled" + if (changed == 0) "" else ", $changed changed here"
    }

    private fun values(option: GoplsOption): String =
        option.enumValues.filter { it.second.isNotEmpty() }.joinToString("") { "<br><br><code>" + GoplsCatalogue.display(it.first, plain = true) + "</code>: " + GoplsDocs.html(it.second) }

    /** A JSON text the way it is typed in a field: a list as words, a map as `NAME=value` words, a text without its quotes. */
    private fun display(option: GoplsOption, json: String): String = when {
        option.type.startsWith("[]") -> GoplsValues.jsonToList(json)
        option.type.startsWith("map[") -> GoplsValues.jsonToEnv(json)
        else -> GoplsCatalogue.display(json, plain = true)
    }

    private fun typed(option: GoplsOption, text: String) {
        if (text.isBlank()) return set(option, null)
        val json = when {
            option.type.startsWith("[]") -> GoplsValues.listToJson(text)
            option.type.startsWith("map[") -> GoplsValues.envToJson(text)
            else -> GoplsCatalogue.toJson(option, text)
        }
        if (json == null) invalid[option.name] = text else set(option, json)
    }

    private fun onChange(field: JTextComponent, action: () -> Unit) = field.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = action()
    })

    // --- Configurable ---

    override fun isModified(): Boolean = options.isNotEmpty() && (invalid.isNotEmpty() || edited != settings.goplsOverrides)

    override fun apply() {
        if (options.isEmpty()) return
        invalid.entries.firstOrNull()?.let { (name, text) ->
            val option = options.first { it.name == name }
            throw ConfigurationException("'$text' is not a value of ${GoplsDocs.title(name)} (${option.type})")
        }
        settings.goplsOverrides = edited.toMap()
        GoLanguageServerControl.restartAll(project)
    }

    override fun reset() {
        pluginDefaults = GoplsDefaults.of(settings).mapValues { Gson().toJson(it.value) }
        edited.clear()
        invalid.clear()
        edited.putAll(settings.goplsOverrides)
        rebuild()
    }

    private companion object {
        /** Code lenses (8) and hints (7) are ticks on the page; the analyzers (hundreds) are a dialog. */
        const val INLINE_FLAGS = 12
    }
}

/** The keys of a map of flags as a list with ticks and a search: for `analyses`, which has a key per analyzer of gopls. */
private class FlagsDialog(project: Project, title: String, private val option: GoplsOption, initial: Map<String, Boolean>) : DialogWrapper(project) {
    private val ticks = LinkedHashMap(initial)
    private val search = SearchTextField(false)
    private val list = CheckBoxList<String>()
    private val doc = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(6, 2)
    }

    init {
        this.title = title
        list.setCheckBoxListListener { index, value -> list.getItemAt(index)?.let { ticks[it] = value } }
        list.addListSelectionListener {
            val key = list.getItemAt(list.selectedIndex)?.let { name -> option.enumKeys.firstOrNull { it.name == name } }
            doc.text = if (key == null) "" else "<html>" + GoplsDocs.summary(key.name, key.doc.ifEmpty { key.name }) + " <i>(default: ${key.default})</i></html>"
        }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = fill()
        })
        fill()
        init()
    }

    private fun fill() {
        val text = search.text.trim()
        list.clear()
        for (key in option.enumKeys) {
            if (text.isEmpty() || key.name.contains(text, ignoreCase = true) || key.doc.contains(text, ignoreCase = true)) list.addItem(key.name, label(key), ticks[key.name] == true)
        }
    }

    /** `QF1001` alone says nothing: the name with what the analyzer is about, in the colour of a comment. */
    private fun label(key: GoplsOption.Key): String {
        val summary = GoplsDocs.summary(key.name, key.doc)
        return if (summary.isEmpty()) key.name else "<html>${key.name} &nbsp;<font color='#${ColorUtil.toHex(UIUtil.getContextHelpForeground())}'>$summary</font></html>"
    }

    fun ticked(): Map<String, Boolean> = ticks

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(search, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        add(doc, BorderLayout.SOUTH)
        preferredSize = Dimension(JBUI.scale(520), JBUI.scale(480))
    }

    override fun getPreferredFocusedComponent(): JComponent = search
}
