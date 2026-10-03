package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.isDefault
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.psi.GoPsiUtil.types
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * The cases a switch lacks, shared by Fill Switch ([GoFillSwitchIntention]) and the exhaustiveness inspection
 * (`ide.inspections.GoExhaustiveSwitchInspection`), so the quick fix inserts exactly what the warning names.
 *
 * - Expression switch over a named non-interface type: its constants in its package (unexported ones only in the own package), in
 *   declaration order. A value counts as present when a case has the same constant value (`exhaustive` rules: constants with equal
 *   values are one member), so aliases like `Ptr = Pointer` never produce a duplicate case.
 * - Type switch over a named interface: the implementing types of the project (`*T` when only the pointer implements it); a case
 *   naming the type with or without `*`, or naming an interface the type implements, counts as present.
 */
object GoSwitchCases {

    /** What a switch lacks: [cases] as source text (`Red`, `pkg.Red`, `*T`), to insert before [anchor] (`default:` or `}`). */
    class Missing(
        val typeName: String,
        val cases: List<String>,
        val anchor: Int,
        val hasDefault: Boolean,
        /** Bit flags (every non-zero constant a distinct power of two): a switch over them is not expected to be exhaustive. */
        val flags: Boolean,
        /** The interface of a type switch, null for an expression switch. */
        val iface: GoTypeSpec?,
        val imports: Collection<String>,
    )

    /**
     * The missing cases of [switch] (a [GoExprSwitchStatement] or a [GoTypeSwitchStatement]), or null when its type is no enum or
     * interface; [interfaceFilter] decides, before the (index) search for implementations, whether a type switch is looked at.
     */
    fun of(switch: PsiElement, file: GoFile, interfaceFilter: (GoTypeSpec) -> Boolean = { true }): Missing? = when (switch) {
        is GoExprSwitchStatement -> constantCases(switch, GoSourceText(file))
        is GoTypeSwitchStatement -> typeCases(switch, GoSourceText(file), interfaceFilter)
        else -> null
    }

    /** The edit inserting [missing] as `case X:` lines at the indentation of [switch]. */
    fun plan(file: GoFile, switch: PsiElement, missing: Missing): GoEditPlan? {
        if (missing.cases.isEmpty()) return null
        val text = file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, switch.textRange.startOffset)
        return GoEditPlan(listOf(GoIntentionText.insertBefore(text, missing.anchor, indent, missing.cases.map { "case $it:" })), missing.imports)
    }

    /** Applies [plan] to [file] in the current write action, then adds its imports (as the intentions do). */
    fun apply(file: GoFile, plan: GoEditPlan) {
        val document = GoImportEdits.document(file) ?: return
        for (edit in plan.edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
        GoImportEdits.commit(file, document)
        if (plan.imports.isEmpty()) return
        for (path in plan.imports) GoImportInserter.addImport(file, document, path)
        GoImportEdits.commit(file, document)
    }

    private fun constantCases(switch: GoExprSwitchStatement, source: GoSourceText): Missing? {
        val tag = switch.tag ?: return null
        val project = switch.project
        val service = GoSemanticService.getInstance(project)
        val type = service.typeOf(tag) as? GoNamedType ?: return null
        val declarationFile = type.declaration.containingFile as? GoFile ?: return null
        val own = source.isOwnPackage(declarationFile)
        val members = GoEnumConstants.of(type, own) ?: return null
        val present = HashSet<PsiElement>()
        val presentNames = HashSet<String>()
        val covered = HashSet<GoConstant>()
        for (clause in switch.exprCaseClauseList) for (expr in clause.expressionList) {
            service.constantValue(expr)?.let(covered::add)
            val ref = expr as? GoReferenceExpression ?: continue
            present += service.resolve(ref)
            ref.identifier.text?.let(presentNames::add)
        }
        val values = members.map { it.value }
        val prefix = if (own) "" else source.prefix(type.pkgPath ?: return null, declarationFile.packageName)
        val cases = LinkedHashSet<String>()
        for (member in members) {
            val constant = member.constant
            if (constant in present || constant.name in presentNames) continue
            val value = member.value
            // a value already in a case, or added for an earlier constant of the same value, would be a duplicate case
            if (value != null && !covered.add(value)) continue
            cases += prefix + constant.name
        }
        val default = switch.exprCaseClauseList.firstOrNull { it.default != null }
        val anchor = (default ?: switch.rbrace)?.textRange?.startOffset ?: return null
        val typeName = if (own) type.name else "${declarationFile.packageName}.${type.name}"
        return Missing(typeName, cases.toList(), anchor, default != null, GoEnumConstants.isFlags(values), null, source.imports)
    }

    private fun typeCases(switch: GoTypeSwitchStatement, source: GoSourceText, interfaceFilter: (GoTypeSpec) -> Boolean): Missing? {
        val expression = switch.guard?.expression ?: return null
        val service = GoSemanticService.getInstance(switch.project)
        val named = service.typeOf(expression) as? GoNamedType ?: return null
        val iface = GoImplementations.interfaceOf(named.declaration) ?: return null
        if (!interfaceFilter(named.declaration)) return null
        val caseTypes = switch.typeCaseClauseList.flatMap { it.types }
        // `T`, `*T`, `pkg.T` all name T
        val present = caseTypes.map { it.text.filterNot(Char::isWhitespace).removePrefix("*").substringAfterLast('.') }.toSet()
        // a case on an interface covers every type that implements it
        val caseInterfaces = caseTypes.mapNotNull { t ->
            val spec = t.typeReferenceExpression?.let(service::resolve) as? GoTypeSpec ?: return@mapNotNull null
            if (spec == named.declaration) null else GoImplementations.interfaceOf(spec)
        }
        val result = ArrayList<String>()
        val implementations = GoImplementations.implementingTypes(named.declaration, GlobalSearchScope.projectScope(switch.project))
            .sortedWith(compareBy({ it.containingFile.virtualFile?.path.orEmpty() }, { it.textOffset }))
        for (spec in implementations) {
            if (spec.typeParameters != null || spec.name in present) continue
            if (!spec.isPublic() && !source.isOwnPackage(spec)) continue
            val type = service.declarationType(spec) as? GoNamedType ?: continue
            if (caseInterfaces.any { service.implements(type, it) || service.implements(GoPointerType(type), it) }) continue
            val name = source.type(type)
            result += if (service.implements(type, iface)) name else "*$name"
        }
        val default = switch.typeCaseClauseList.firstOrNull { it.isDefault }
        val anchor = (default ?: switch.rbrace)?.textRange?.startOffset ?: return null
        val declarationFile = named.declaration.containingFile as? GoFile
        val typeName = if (source.isOwnPackage(declarationFile) || declarationFile == null) named.name else "${declarationFile.packageName}.${named.name}"
        return Missing(typeName, result, anchor, default != null, false, named.declaration, source.imports)
    }
}
