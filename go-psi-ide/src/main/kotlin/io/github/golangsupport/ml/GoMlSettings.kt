package io.github.golangsupport.ml

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Machine-wide switches of the ML completion ranking (Settings | Go | Smart Completion). Kept in go-psi-ide so that the
 * ranker ([GoMlCompletionRanker]) and the host plugin's settings page share them; the page exists only in builds that
 * bundle the models (`-PmlEnabled=true`, [GoMlModels.isBundled]).
 */
@Service(Service.Level.APP)
@State(name = "GoMlCompletion", storages = [Storage("golang-support-ml.xml")])
class GoMlSettings : SimplePersistentStateComponent<GoMlSettings.Options>(Options()) {
    class Options : BaseState() {
        /** Off: the deterministic order of the plugin applies, the models are not even loaded. */
        var enabled by property(true)
        /** A directory with `lm.cml` and `rank.cml` to use instead of the bundled models (for trying a new training); empty: bundled. */
        var modelDirectory by string("")
        /** Grey "ML" after the rows the model ordered. */
        var showMarker by property(true)
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var showMarker: Boolean
        get() = state.showMarker
        set(value) { state.showMarker = value }

    var modelDirectory: String
        get() = state.modelDirectory ?: ""
        set(value) { state.modelDirectory = value }

    companion object {
        fun getInstance(): GoMlSettings = service()
    }
}
