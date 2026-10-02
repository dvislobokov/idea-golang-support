package io.github.golangsupport.lang.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import io.github.golangsupport.lang.GoLanguage

/** Creates Go PSI from text by parsing a dummy file. */
object GoElementFactory {

    @JvmStatic
    @JvmOverloads
    fun createFileFromText(project: Project, text: String, name: String = "dummy.go"): GoFile =
        PsiFileFactory.getInstance(project).createFileFromText(name, GoLanguage, text) as GoFile

    /** An `IDENTIFIER` leaf with the given text. */
    @JvmStatic
    fun createIdentifier(project: Project, name: String): PsiElement {
        val clause = createFileFromText(project, "package $name").packageClause
        val identifier = clause?.identifier
        if (identifier == null || identifier.text != name) {
            throw IncorrectOperationException("'$name' is not a Go identifier")
        }
        return identifier
    }

    /** `alias "path"` as an import spec, or `null` when the text does not parse as one. */
    @JvmStatic
    fun createImportSpec(project: Project, alias: String?, path: String): GoImportSpec? {
        val spec = (if (alias != null) "$alias " else "") + "\"" + path + "\""
        val file = createFileFromText(project, "package p\nimport $spec\n")
        return PsiTreeUtil.findChildOfType(file, GoImportSpec::class.java)
    }
}
