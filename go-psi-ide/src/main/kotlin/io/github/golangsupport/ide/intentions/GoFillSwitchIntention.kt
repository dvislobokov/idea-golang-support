package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.isDefault
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.psi.GoPsiUtil.types
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * Fill switch: the missing `case`s of a switch, added after the existing ones and before `default` (which stays last).
 *
 * - An expression switch over a value of a named type with constants of that type in its package (an `iota` enum): a
 *   `case C:` for every constant not in a case yet (unexported ones only in their own package), in declaration order.
 * - A type switch over a value of a named interface: a case for every type of the project that implements it (`*T` when only
 *   the pointer does), generic types left out; a case naming the type with or without `*` counts as present.
 */
class GoFillSwitchIntention : GoCodeActionIntention() {
    override val defaultText: String = "Fill switch"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val switch = PsiTreeUtil.getParentOfType(leaf, GoExprSwitchStatement::class.java, GoTypeSwitchStatement::class.java) ?: return null
        if (GoPsiUtil.functionOwner(switch) != GoPsiUtil.functionOwner(leaf)) return null
        val source = GoSourceText(file)
        val (cases, anchor) = when (switch) {
            is GoExprSwitchStatement -> constantCases(switch, source) to anchorOf(switch.exprCaseClauseList.firstOrNull { it.default != null }, switch.rbrace)
            is GoTypeSwitchStatement -> typeCases(switch, source) to anchorOf(switch.typeCaseClauseList.firstOrNull { it.isDefault }, switch.rbrace)
            else -> return null
        }
        if (cases.isNullOrEmpty() || anchor == null) return null
        val text = file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, switch.textRange.startOffset)
        return GoEditPlan(listOf(GoIntentionText.insertBefore(text, anchor, indent, cases.map { "case $it:" })), source.imports)
    }

    private fun anchorOf(default: PsiElement?, rbrace: PsiElement?): Int? = (default ?: rbrace)?.textRange?.startOffset

    private fun constantCases(switch: GoExprSwitchStatement, source: GoSourceText): List<String>? {
        val tag = switch.tag ?: return null
        val service = GoSemanticService.getInstance(switch.project)
        val type = service.typeOf(tag) as? GoNamedType ?: return null
        if (type.underlying() is GoInterfaceType) return null
        val declarationFile = type.declaration.containingFile as? GoFile ?: return null
        if (declarationFile.packageName == "builtin") return null
        val own = source.isOwnPackage(declarationFile)
        val constants = GoPackageModel.getInstance(switch.project).scopeOf(declarationFile).files.flatMap { it.consts }
            .filter { it.name != null && it.name != "_" && (own || it.isPublic()) && GoTypePredicates.identical(service.declarationType(it), type) }
        if (constants.isEmpty()) return null
        val present = HashSet<PsiElement>()
        val presentNames = HashSet<String>()
        for (clause in switch.exprCaseClauseList) for (expr in clause.expressionList) {
            val ref = expr as? GoReferenceExpression ?: continue
            present += service.resolve(ref)
            ref.identifier?.text?.let(presentNames::add)
        }
        val prefix = if (own) "" else source.prefix(type.pkgPath ?: return null, declarationFile.packageName)
        return constants.filter { it !in present && it.name !in presentNames }.map { prefix + it.name }.distinct()
    }

    private fun typeCases(switch: GoTypeSwitchStatement, source: GoSourceText): List<String>? {
        val expression = switch.guard?.expression ?: return null
        val service = GoSemanticService.getInstance(switch.project)
        val named = service.typeOf(expression) as? GoNamedType ?: return null
        val iface = GoImplementations.interfaceOf(named.declaration) ?: return null
        // `T`, `*T`, `pkg.T` all name T
        val present = switch.typeCaseClauseList.flatMap { it.types }.map { it.text.filterNot(Char::isWhitespace).removePrefix("*").substringAfterLast('.') }.toSet()
        val result = ArrayList<String>()
        val implementations = GoImplementations.implementingTypes(named.declaration, GlobalSearchScope.projectScope(switch.project))
            .sortedWith(compareBy({ it.containingFile.virtualFile?.path.orEmpty() }, { it.textOffset }))
        for (spec in implementations) {
            if (spec.typeParameters != null || spec.name in present) continue
            if (!spec.isPublic() && !source.isOwnPackage(spec)) continue
            val type = service.declarationType(spec) as? GoNamedType ?: continue
            val name = source.type(type)
            result += if (service.implements(type, iface)) name else "*$name"
        }
        return result
    }
}
