package io.github.golangsupport.ide.completion

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Machine-wide switches of the completion helpers that need no model (Settings | Go | Editor and Completion in the host): the
 * field-to-field "mapping" items ([GoMappingCompletion]) and the memory of accepted items ([GoAcceptanceMemory]). Kept in
 * go-psi-ide so that the completion code and the host's settings page share them.
 */
@Service(Service.Level.APP)
@State(name = "GoCompletionAssist", storages = [Storage("golang-support-completion.xml")])
class GoCompletionAssistSettings : SimplePersistentStateComponent<GoCompletionAssistSettings.Options>(Options()) {
    class Options : BaseState() {
        /** `Name: src.Name,` / `dst.Name = src.Name` items and "Map all remaining fields from src". */
        var mappingEnabled by property(true)
        /** Items chosen before in this project go up in the lists where they were chosen (per context kind). */
        var acceptanceEnabled by property(true)
        /** The ML ranker's bonus per accepted item: `weight × ln(1 + count)` in score units (the rankers without a model order by the count directly). */
        var acceptanceWeight by property(DEFAULT_ACCEPTANCE_WEIGHT)
    }

    var mappingEnabled: Boolean
        get() = state.mappingEnabled
        set(value) { state.mappingEnabled = value }

    var acceptanceEnabled: Boolean
        get() = state.acceptanceEnabled
        set(value) { state.acceptanceEnabled = value }

    var acceptanceWeight: Double
        get() = state.acceptanceWeight.toString().toDouble()
        set(value) { state.acceptanceWeight = value.toFloat() }

    companion object {
        /**
         * The linear ranker's scores of two candidates that are close differ by a few tenths and a clear loss by 2 and more (the
         * logits of `LinearRanker`): 0.3 × ln(1 + 3) ≈ 0.42 turns a close call after three acceptances, not a clear one.
         */
        const val DEFAULT_ACCEPTANCE_WEIGHT = 0.3f

        fun getInstance(): GoCompletionAssistSettings = service()
    }
}
