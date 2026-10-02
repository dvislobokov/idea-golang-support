package io.github.golangsupport.ide.structure

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.ide.navigation.GoItemPresentation
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import javax.swing.Icon

/**
 * Breadcrumbs (and sticky lines): `T › (T) M() › if › func()`. Declarations show their name,
 * control statements their keyword, case clauses `case`/`default`.
 */
class GoBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(GoLanguage)

    override fun acceptElement(element: PsiElement): Boolean = when (element) {
        is GoFunctionDeclaration, is GoMethodDeclaration, is GoTypeSpec, is GoFieldDefinition, is GoMethodSpec -> (element as GoNamedElement).name != null
        is GoFunctionLit, is GoIfStatement, is GoForStatement, is GoExprSwitchStatement, is GoTypeSwitchStatement, is GoSelectStatement,
        is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause,
        -> true
        else -> false
    }

    override fun getElementInfo(element: PsiElement): String = when (element) {
        is GoFunctionDeclaration -> element.name + "()"
        is GoMethodDeclaration -> {
            val receiver = element.receiverTypeName
            (if (receiver != null) "($receiver) " else "") + element.name + "()"
        }
        is GoMethodSpec -> element.name + "()"
        is GoNamedElement -> element.name.orEmpty()
        is GoFunctionLit -> "func()"
        is GoIfStatement -> "if"
        is GoForStatement -> "for"
        is GoExprSwitchStatement, is GoTypeSwitchStatement -> "switch"
        is GoSelectStatement -> "select"
        is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause -> if (element.text.startsWith("default")) "default" else "case"
        else -> ""
    }

    override fun getElementTooltip(element: PsiElement): String? =
        (element as? GoNamedElement)?.let { GoItemPresentation.detailedText(it) }

    override fun getElementIcon(element: PsiElement): Icon? = GoIdeIcons.forElement(element)
}
