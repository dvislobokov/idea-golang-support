package io.github.golangsupport.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import io.github.golangsupport.GoIcons
import javax.swing.Icon

/** The id differs from the `go` of the JetBrains Go plugin on purpose; the two plugins are declared incompatible anyway. */
object GoLanguage : Language("Go")

object GoFileType : LanguageFileType(GoLanguage) {
    override fun getName(): String = "Go"
    override fun getDisplayName(): String = "Go"
    override fun getDescription(): String = "Go source file"
    override fun getDefaultExtension(): String = "go"
    override fun getIcon(): Icon = GoIcons.File
}

class GoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GoLanguage) {
    override fun getFileType(): FileType = GoFileType
    override fun toString(): String = "Go File"

    val isTestFile: Boolean get() = name.endsWith(TEST_SUFFIX)

    companion object {
        const val TEST_SUFFIX = "_test.go"
    }
}
