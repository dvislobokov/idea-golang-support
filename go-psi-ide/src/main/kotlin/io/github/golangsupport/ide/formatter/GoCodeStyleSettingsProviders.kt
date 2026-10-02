package io.github.golangsupport.ide.formatter

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.golangsupport.lang.GoLanguage

/**
 * Go code style defaults: gofmt indents with tabs and has no options, so only the standard indent
 * options are exposed. Tabs are shown 4 columns wide (gofmt's own tab width of 8 only matters for
 * its internal column computation, which does not depend on these settings).
 */
class GoLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = GoLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.USE_TAB_CHARACTER = true
        indentOptions.TAB_SIZE = 4
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.SMART_TABS = false
        commonSettings.LINE_COMMENT_ADD_SPACE = true
        commonSettings.LINE_COMMENT_AT_FIRST_COLUMN = false
        commonSettings.BLOCK_COMMENT_AT_FIRST_COLUMN = false
        commonSettings.KEEP_BLANK_LINES_IN_CODE = 1
        commonSettings.KEEP_BLANK_LINES_IN_DECLARATIONS = 1
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = IndentOptionsEditor()

    override fun customizeSettings(consumer: com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.COMMENTER_SETTINGS) {
            consumer.showStandardOptions("LINE_COMMENT_ADD_SPACE", "LINE_COMMENT_AT_FIRST_COLUMN")
        }
    }

    override fun getCodeSample(settingsType: SettingsType): String = CODE_SAMPLE

    companion object {
        private val CODE_SAMPLE = """
            package sample

            import (
            	"fmt"
            	"strings"
            )

            // Point is a point in the plane.
            type Point struct {
            	X, Y  int    `json:"x"`
            	Label string // optional
            }

            const (
            	small = iota // 0
            	large        // 1
            )

            func (p *Point) String() string {
            	switch {
            	case p.X > 0 && p.Y > 0:
            		return fmt.Sprintf("(%d, %d)", p.X*2+1, p.Y)
            	default:
            		return strings.Repeat("-", p.X)
            	}
            }
        """.trimIndent() + "\n"
    }
}

/** Registers the Go page under Settings | Editor | Code Style. */
class GoCodeStyleSettingsProvider : CodeStyleSettingsProvider() {

    override fun getLanguage(): Language = GoLanguage

    override fun getConfigurableDisplayName(): String = "Go"

    override fun createConfigurable(settings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(settings, modelSettings, configurableDisplayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(GoLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                    }
                }
        }
}
