package io.github.golangsupport.ide.editor

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition

/** Words of a Go file for the id index: identifiers, comments and literals (strings may hold names). */
class GoWordsScanner : DefaultWordsScanner(
    GoLexer(),
    TokenSet.create(GoTypes.IDENTIFIER),
    GoTokenSets.COMMENTS,
    GoTokenSets.LITERALS,
) {
    init {
        setMayHaveFileRefsInLiterals(true)
    }
}

/** Find Usages entry point for every named Go declaration. The usages themselves come from reference search. */
class GoFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = GoWordsScanner()

    override fun canFindUsagesFor(element: PsiElement): Boolean =
        element is GoNamedElement && !element.name.isNullOrEmpty() && element.name != "_"

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = typeName(element)

    override fun getDescriptiveName(element: PsiElement): String = (element as? GoNamedElement)?.name.orEmpty()

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = (element as? GoNamedElement)?.name.orEmpty()

    companion object {
        /** Lower-case kind word used in Find Usages titles and rename dialogs. */
        @JvmStatic
        fun typeName(element: PsiElement): String = when (element) {
            is GoFunctionDeclaration -> "function"
            is GoMethodDeclaration, is GoMethodSpec -> "method"
            is GoTypeSpec, is GoTypeParamDefinition -> "type"
            is GoVarDefinition -> "variable"
            is GoConstDefinition -> "constant"
            is GoParamDefinition, is GoReceiver -> "parameter"
            is GoFieldDefinition, is GoAnonymousFieldDefinition -> "field"
            is GoLabelDefinition -> "label"
            is GoPackageClause, is GoImportSpec -> "package"
            else -> ""
        }
    }
}
