package io.github.golangsupport.ide.inspections.printf

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

/**
 * The user's corrections to [GoPrintfCalls]: functions to treat as printf-like ([State.extra]) and printf-like ones to ignore
 * ([State.excluded]), by vet's full name (`fmt.Printf`, `example.com/log.Infof`, `(*example.com/log.Logger).Infof`). Filled by the
 * intentions Mark as / Exclude string formatting function; the host's settings page (Settings | Go | Linters) edits the same lists. Application-level:
 * the names are import paths, the same in every project.
 */
@Service(Service.Level.APP)
@State(name = "GoPrintfFunctions", storages = [Storage("go-printf-functions.xml")])
class GoPrintfFunctions : PersistentStateComponent<GoPrintfFunctions.State> {

    class State {
        /** Functions checked as printf-like although not recognized: `Printf`-like when the parameter before `...any` is a string. */
        @get:XCollection(style = XCollection.Style.v2)
        var extra: MutableList<String> = ArrayList()

        /** Printf-like functions (known or found as wrappers) that are not checked. */
        @get:XCollection(style = XCollection.Style.v2)
        var excluded: MutableList<String> = ArrayList()
    }

    @Volatile private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** The functions marked as printf-like, as the settings page edits them (a copy; set replaces the list). */
    var extra: List<String>
        get() = state.extra.toList()
        set(value) { state.extra = clean(value) }

    /** The printf-like functions that are not checked, as the settings page edits them. */
    var excluded: List<String>
        get() = state.excluded.toList()
        set(value) { state.excluded = clean(value) }

    private fun clean(names: List<String>): MutableList<String> = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()

    fun isExtra(name: String): Boolean = name in state.extra

    fun isExcluded(name: String): Boolean = name in state.excluded

    /** Mark as string formatting function: [name] is checked from now on (and no longer excluded). */
    fun mark(name: String) {
        state.excluded.remove(name)
        if (name !in state.extra) state.extra.add(name)
    }

    /** Exclude string formatting function: [name] is no longer checked (a marked one is simply unmarked). */
    fun exclude(name: String) {
        if (state.extra.remove(name)) return
        if (name !in state.excluded) state.excluded.add(name)
    }

    companion object {
        fun getInstance(): GoPrintfFunctions = ApplicationManager.getApplication().getService(GoPrintfFunctions::class.java)
    }
}
