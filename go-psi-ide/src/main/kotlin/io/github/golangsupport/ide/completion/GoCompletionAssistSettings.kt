package io.github.golangsupport.ide.completion

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Machine-wide switches of the completion helpers that need no model (Settings | Go | Editor and Completion in the host): the
 * field-to-field "mapping" items ([GoMappingCompletion]). Kept in
 * go-psi-ide so that the completion code and the host's settings page share them.
 */
@Service(Service.Level.APP)
@State(name = "GoCompletionAssist", storages = [Storage("golang-support-completion.xml")])
class GoCompletionAssistSettings : SimplePersistentStateComponent<GoCompletionAssistSettings.Options>(Options()) {
    class Options : BaseState() {
        /** `Name: src.Name,` / `dst.Name = src.Name` items and "Map all remaining fields from src". */
        var mappingEnabled by property(true)
    }

    var mappingEnabled: Boolean
        get() = state.mappingEnabled
        set(value) { state.mappingEnabled = value }

    companion object {
        fun getInstance(): GoCompletionAssistSettings = service()
    }
}
