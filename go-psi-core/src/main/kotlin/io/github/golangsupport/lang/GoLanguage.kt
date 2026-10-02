package io.github.golangsupport.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.EmptyIcon
import javax.swing.Icon

/** The id differs from the `go` of the JetBrains Go plugin on purpose; the two plugins are declared incompatible anyway. */
object GoLanguage : Language("Go") {
    private fun readResolve(): Any = GoLanguage
    override fun getDisplayName(): String = "Go"
    override fun isCaseSensitive(): Boolean = true
}

object GoFileType : LanguageFileType(GoLanguage) {
    // The SVG lives in the root module (one jar after step 3); the tests of this module have none.
    private val ICON: Icon by lazy { IconLoader.findIcon("/icons/go.svg", GoFileType::class.java) ?: EmptyIcon.ICON_16 }

    override fun getName(): String = "Go"
    override fun getDisplayName(): String = "Go"
    override fun getDescription(): String = "Go source file"
    override fun getDefaultExtension(): String = "go"
    override fun getIcon(): Icon = ICON
}
