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
        /** Grey text to the end of the line from the transformer ([GoNnInlineCompletionProvider]); off: the network is not even loaded. */
        var inlineEnabled by property(true)
        /** `confProd` the suggestion needs to be shown (the engine's default gate: 0.7 shows ~26 % of positions with 93 % exact lines, 0.8 ~20 % at 95 %). */
        var inlineThreshold by property(0.7f)
        /** Show suggestions that are punctuation only (`)`, `};`): off by default, the brackets of the editor do that already. */
        var inlineShowClosers by property(false)
        /** Gate on the confidence of the code tokens only, so a line with a string literal (`fmt.Errorf("…")`) is shown with the text guessed. */
        var inlineGuessStrings by property(true)
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

    var inlineEnabled: Boolean
        get() = state.inlineEnabled
        set(value) { state.inlineEnabled = value }

    var inlineThreshold: Double
        get() = state.inlineThreshold.toString().toDouble()
        set(value) { state.inlineThreshold = value.toFloat() }

    var inlineShowClosers: Boolean
        get() = state.inlineShowClosers
        set(value) { state.inlineShowClosers = value }

    var inlineGuessStrings: Boolean
        get() = state.inlineGuessStrings
        set(value) { state.inlineGuessStrings = value }

    companion object {
        fun getInstance(): GoMlSettings = service()
    }
}
