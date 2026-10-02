package io.github.golangsupport.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class GoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GoLanguage) {
    override fun getFileType(): FileType = GoFileType
    override fun toString(): String = "Go File"

    val isTestFile: Boolean get() = name.endsWith(TEST_SUFFIX)

    companion object {
        const val TEST_SUFFIX = "_test.go"
    }
}
