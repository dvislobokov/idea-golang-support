package io.github.golangsupport.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object GoFileType : LanguageFileType(GoLanguage) {
    override fun getName(): String = "Go"

    override fun getDescription(): String = "Go source file"

    override fun getDefaultExtension(): String = "go"

    override fun getIcon(): Icon = GoIcons.FILE
}
