package io.github.golangsupport.ide.folding

import com.intellij.application.options.editor.CodeFoldingOptionsProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.BeanConfigurable
import com.intellij.util.xmlb.XmlSerializerUtil

/** Which one-line regions of [GoFoldingBuilder] are collapsed by default; names and defaults as in GoLand's Code Folding page. */
@Service(Service.Level.APP)
@State(name = "GoFoldingSettings", storages = [Storage("go-folding.xml")])
class GoFoldingSettings : PersistentStateComponent<GoFoldingSettings> {
    var collapseErrorHandlingIf: Boolean = true
    var collapseSingleReturnFunctions: Boolean = true
    var collapseCaseClauses: Boolean = true
    var collapseEmptyFunctions: Boolean = true
    var collapseEmptyTypes: Boolean = true

    override fun getState(): GoFoldingSettings = this

    override fun loadState(state: GoFoldingSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        @JvmStatic
        fun getInstance(): GoFoldingSettings = ApplicationManager.getApplication().getService(GoFoldingSettings::class.java)
    }
}

/** The Go section of Settings | Editor | General | Code Folding ("Fold by default"). */
class GoCodeFoldingOptionsProvider : BeanConfigurable<GoFoldingSettings>(GoFoldingSettings.getInstance(), "Go"), CodeFoldingOptionsProvider {
    init {
        val s = instance
        checkBox("One-line 'if' blocks for error handling", s::collapseErrorHandlingIf)
        checkBox("One-line functions with a single 'return'", s::collapseSingleReturnFunctions)
        checkBox("One-line case clauses", s::collapseCaseClauses)
        checkBox("Empty functions", s::collapseEmptyFunctions)
        checkBox("Empty struct or interface type definitions", s::collapseEmptyTypes)
    }
}
