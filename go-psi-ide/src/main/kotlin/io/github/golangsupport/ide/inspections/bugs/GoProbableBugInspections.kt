package io.github.golangsupport.ide.inspections.bugs

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoEditFix
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.inspections.GoInspectionText
import io.github.golangsupport.ide.inspections.GoRenameVariableFix
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.inspections.style.GoPackageReceivers
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse

/**
 * GoLand's "Defer/go statement calls 'recover' or 'panic' directly" (`GoDeferGo`): `defer recover()` does not stop a panic (recover
 * works only when called by the deferred function itself), `defer panic(x)` / `go panic(x)` panic later than they read. Fix: wrap the
 * call in a function literal, `defer func() { recover() }()`. GoLand's text and range (the whole statement, seen live).
 */
class GoDeferGoInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val (keyword, expression) = when (element) {
            is GoDeferStatement -> "defer" to element.expression
            is GoGoStatement -> "go" to element.expression
            else -> return
        }
        val call = GoLintPsi.unparen(expression) as? GoCallExpr ?: return
        val ref = GoLintPsi.calleeReference(call) ?: return
        val name = ref.identifier?.text
        if (ref.expression != null || name != "recover" && name != "panic") return
        if (GoLintPsi.calleeTarget(call)?.let(GoLintPsi::isBuiltin) != true) return
        holder.registerProblem(element, "$keyword should not call $name() directly", WRAP)
    }

    private companion object {
        val WRAP = GoEditFix("Wrap in a function literal") { statement ->
            val expression = when (statement) {
                is GoDeferStatement -> statement.expression
                is GoGoStatement -> statement.expression
                else -> null
            }
            val call = GoLintPsi.unparen(expression) as? GoCallExpr ?: return@GoEditFix null
            listOf(GoEditPlan.Edit(call.textRange.startOffset, call.textRange.endOffset, "func() { ${call.text} }()"))
        }
    }
}

/** Declarations of locals and parameters checked by the name-collision inspections, with their kind for the message. */
internal object GoLocalNames {
    fun kind(element: PsiElement): String? = when (element) {
        is GoVarDefinition -> "Variable"
        is GoConstDefinition -> "Constant"
        is GoParamDefinition -> "Parameter"
        is GoReceiver -> "Receiver"
        is GoTypeParamDefinition -> "Type parameter"
        is GoTypeSpec -> "Type"
        is GoFunctionDeclaration -> "Function"
        else -> null
    }
}

/**
 * GoLand's "Imported package name as a name identifier" (`GoImportUsedAsName`): a local variable, constant, parameter, receiver or local
 * type named like a package the file imports (`strings := …` with `"strings"` imported): the package is unreachable in that scope.
 * Blank and dot imports do not count. Fix: rename.
 */
class GoImportUsedAsNameInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoNamedElement || element is GoFunctionDeclaration) return
        val kind = GoLocalNames.kind(element) ?: return
        val name = element.name ?: return
        if (name == "_" || element !is GoParamDefinition && element !is GoReceiver && GoPsiUtil.functionOwner(element) == null && element !is GoTypeParamDefinition) return
        if (file.imports.none { !it.isBlank && !it.isDot && GoScopes.importName(it) == name }) return
        holder.registerProblem(element.nameIdentifier ?: element, "$kind '$name' collides with imported package name", GoRenameVariableFix("Rename"))
    }
}

/**
 * GoLand's "Reserved word used as name" (`GoReservedWordUsedAsName`): a declaration named after a predeclared identifier (`len := 3`,
 * `func string()`, `type error struct{}`, parameter `new`): the builtin is hidden in that scope. Struct fields and methods are not
 * reported (they never hide the builtin). Fix: rename.
 */
class GoReservedWordUsedAsNameInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoNamedElement || element is GoFieldDefinition || element is GoMethodDeclaration) return
        val kind = GoLocalNames.kind(element) ?: return
        val name = element.name ?: return
        if (!GoUniverse.isBuiltin(name) || file.packageName == "builtin") return
        val what = when (name) {
            in GoUniverse.FUNCTIONS -> "function"
            in GoUniverse.CONSTANTS -> "constant"
            else -> "type"
        }
        holder.registerProblem(element.nameIdentifier ?: element, "$kind '$name' collides with the 'builtin' $what", GoRenameVariableFix("Rename"))
    }
}

/**
 * GoLand's "Irregular usage of 'iota'" (`GoIrregularIota`), by its own description: a spec of a `const (...)` group whose expression list
 * is textually identical to the one of an earlier spec, with at least one spec between them and only specs without an expression list
 * between them (`_` is such a spec): `a = iota; b; c = iota` reports `c = iota`, the implicit repetition already gives it that value.
 * Adjacent repeats (`A0 = iota; A1 = iota`), `const X = iota` and `H0 = iota; H1 = 7; H2` are not reported. The list must contain a
 * bare `iota` element (`iota`, `iota, iota`): GoLand stays quiet on `D0 = iota * 2; D1; D2 = iota * 2` and on `1 << iota` with a gap
 * (seen live). The type is part of the comparison (`l Weekday = iota` after `j Weekday = iota; k`). The whole spec is reported. Fix:
 * remove the repeated type and expressions.
 */
class GoIrregularIotaInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoConstSpec) return
        val declaration = element.parent as? GoConstDeclaration ?: return
        if (declaration.lparen == null || element.expressionList.none(::isIota)) return
        val before = declaration.constSpecList.takeWhile { it !== element }
        val gap = before.takeLastWhile { it.expressionList.isEmpty() }
        if (gap.isEmpty()) return
        val previous = before.getOrNull(before.size - gap.size - 1) ?: return
        if (norm(previous.type?.text) != norm(element.type?.text) || norm(previous.expressionList.joinToString(",") { it.text }) != norm(element.expressionList.joinToString(",") { it.text })) return
        holder.registerProblem(element, "Irregular usage of 'iota'", REMOVE_REPETITION)
    }

    companion object {
        /** A bare reference to the predeclared `iota`. */
        fun isIota(e: GoExpression): Boolean = e is GoReferenceExpression && e.expression == null && e.identifier?.text == "iota" &&
            GoSemanticService.getInstance(e.project).resolve(e).any { t -> GoLintPsi.isBuiltin(t) }

        private fun norm(s: String?): String = s?.filterNot { it.isWhitespace() } ?: ""

        private val REMOVE_REPETITION = GoEditFix("Remove the repeated expression") { spec ->
            val s = spec as? GoConstSpec ?: return@GoEditFix null
            val last = s.constDefinitionList.lastOrNull() ?: return@GoEditFix null
            listOf(GoEditPlan.Edit(last.textRange.endOffset, s.textRange.endOffset, ""))
        }
    }
}

/**
 * GoLand's "Mixed value and pointer receivers" (`GoMixedReceiverTypes`): methods of one type declared with both value and pointer
 * receivers across the package (the files of the directory with the same package clause, read through stubs). As in GoLand (seen live),
 * the name of every method of the type is reported with GoLand's text. Fixes, on the methods of the minority kind (value receivers on a
 * tie): change the receiver to a pointer / to a value.
 */
class GoMixedReceiverTypesInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoMethodDeclaration) return
        val typeName = element.receiverTypeName ?: return
        val identifier = element.nameIdentifier ?: return
        val receivers = GoPackageReceivers.of(file)[typeName] ?: return
        val pointers = receivers.count { it.pointer }
        val values = receivers.size - pointers
        if (pointers == 0 || values == 0) return
        val pointer = element.isPointerReceiver
        val minority = if (pointer) pointers < values else values <= pointers
        val fixes = if (minority && element.receiver?.type != null) arrayOf<LocalQuickFix>(if (pointer) TO_VALUE else TO_POINTER) else emptyArray()
        holder.registerProblem(identifier, "Struct $typeName has methods on both value and pointer receivers. Such usage is not recommended by the Go Documentation.", *fixes)
    }

    private companion object {
        /** The receiver type of the method whose name the problem is on. */
        private fun receiverType(name: PsiElement): PsiElement? = (name.parent as? GoMethodDeclaration)?.receiver?.type

        val TO_POINTER = GoEditFix("Change receiver to pointer") { name ->
            val type = receiverType(name) ?: return@GoEditFix null
            if (type.text.startsWith("*")) return@GoEditFix null
            listOf(GoEditPlan.Edit(type.textRange.startOffset, type.textRange.startOffset, "*"))
        }
        val TO_VALUE = GoEditFix("Change receiver to value") { name ->
            val type = receiverType(name) ?: return@GoEditFix null
            if (!type.text.startsWith("*")) return@GoEditFix null
            listOf(GoEditPlan.Edit(type.textRange.startOffset, type.textRange.startOffset + 1, ""))
        }
    }
}

/**
 * GoLand's "Type assertion on errors fails on wrapped errors" (`GoTypeAssertionOnErrors`, errorlint): `err.(*MyErr)` on a value of type
 * `error` misses an error wrapped with `%w`; `errors.As` unwraps. `Is` / `As` / `Unwrap` methods are skipped (they inspect one level by
 * design). Fix for `if e, ok := err.(*T); ok {` (the `ok` used only there): `var e *T` before it and `if errors.As(err, &e) {`; when `e` is
 * already visible at the `if` or mentioned after it, the variable gets a free name (`e2`) and its uses inside the `if` follow.
 */
class GoTypeAssertionOnErrorsInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoTypeAssertionExpr) return
        val method = PsiTreeUtil.getParentOfType(element, GoMethodDeclaration::class.java)
        if (method?.name in setOf("Is", "As", "Unwrap")) return
        val type = GoSemanticService.getInstance(file.project).typeOf(element.expression)
        if (!GoAnalysisPsi.isError(type)) return
        val fixes = if (rewritable(element)?.let { targetName(element, it) } != null) arrayOf<LocalQuickFix>(GoUseErrorsAsFix()) else emptyArray()
        holder.registerProblem(element, "Type assertion on errors fails on wrapped errors", *fixes)
    }

    companion object {
        /** The `if` of `if v, ok := err.(T); ok { … }` when the fix applies, else null. */
        fun rewritable(assertion: GoTypeAssertionExpr): GoIfStatement? {
            val declaration = assertion.parent as? GoShortVarDeclaration ?: return null
            if (declaration.expressionList.singleOrNull() !== assertion || declaration.varDefinitionList.size != 2) return null
            val statement = declaration.parent as? GoSimpleStatement ?: declaration
            val ifStatement = statement.parent as? GoIfStatement ?: return null
            if (ifStatement.initStatement !== statement || ifStatement.parent !is GoBlock) return null
            val (value, ok) = declaration.varDefinitionList
            val condition = ifStatement.condition as? GoReferenceExpression ?: return null
            if (condition.expression != null || condition.identifier?.text != ok.name || value.name == "_") return null
            val uses = PsiTreeUtil.findChildrenOfType(ifStatement, GoReferenceExpression::class.java).count { it.expression == null && it.identifier?.text == ok.name }
            if (uses != 1) return null
            return ifStatement
        }

        /**
         * The name of the `var` the fix declares before [ifStatement] (in the enclosing block): the asserted variable's own name when nothing
         * named so is visible at the `if` (a redeclaration, or a shadowed outer variable) or mentioned after it, else `e2`, `e3`, … free in the
         * `if` too; null when none is.
         */
        fun targetName(assertion: GoTypeAssertionExpr, ifStatement: GoIfStatement): String? {
            val value = (assertion.parent as? GoShortVarDeclaration)?.varDefinitionList?.firstOrNull()?.name ?: return null
            val block = ifStatement.parent as? GoBlock ?: return null
            val after = block.statementList.filter { it.textRange.startOffset > ifStatement.textRange.startOffset }
            fun free(name: String) = GoScopes.resolveName(ifStatement, name).isEmpty() && after.none { GoSimplePsi.mentions(it, name) }
            if (free(value)) return value
            return (2..9).map { "$value$it" }.firstOrNull { free(it) && !GoSimplePsi.mentions(ifStatement, it) }
        }
    }
}

/** `if e, ok := err.(*T); ok {` → `var e *T` + `if errors.As(err, &e) {`, importing `errors` when needed. */
class GoUseErrorsAsFix : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with 'errors.As'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val assertion = descriptor.psiElement as? GoTypeAssertionExpr ?: return
        val ifStatement = GoTypeAssertionOnErrorsInspection.rewritable(assertion) ?: return
        val file = assertion.containingFile as? GoFile ?: return
        val declaration = assertion.parent as GoShortVarDeclaration
        val definition = declaration.varDefinitionList[0]
        val value = GoTypeAssertionOnErrorsInspection.targetName(assertion, ifStatement) ?: return
        val type = assertion.type?.text ?: return
        val condition = ifStatement.condition ?: return
        val service = GoSemanticService.getInstance(project)
        // A fresh name renames the uses inside the `if` (all after the header, so they are edited first, from the end).
        val renames = if (value == definition.name) emptyList() else PsiTreeUtil.findChildrenOfType(ifStatement, GoReferenceExpression::class.java)
            .filter { it.expression == null && it.identifier.text == definition.name && it.textRange.startOffset > condition.textRange.endOffset && service.resolve(it).singleOrNull() == definition }
        val source = GoSourceText(file)
        val prefix = source.prefix("errors", "errors")
        val document = GoImportEdits.document(file) ?: return
        val text = document.charsSequence
        val indent = GoInspectionText.indentAt(text, ifStatement.textRange.startOffset)
        for (ref in renames.sortedByDescending { it.textRange.startOffset }) document.replaceString(ref.identifier.textRange.startOffset, ref.identifier.textRange.endOffset, value)
        val headerStart = declaration.textRange.startOffset
        document.replaceString(headerStart, condition.textRange.endOffset, "${prefix}As(${assertion.expression.text}, &$value)")
        document.insertString(ifStatement.textRange.startOffset, "var $value $type\n$indent")
        GoImportEdits.commit(file, document)
        if (source.imports.isEmpty()) return
        for (path in source.imports) GoImportInserter.addImport(file, document, path)
        GoImportEdits.commit(file, document)
    }
}

/**
 * GoLand's "Assignment to a receiver" (`GoAssignmentToReceiver`, group Control flow issues): `c = …`, `c++`, `c += …` on the receiver
 * itself. On a value receiver only the method's copy changes ("doesn't propagate to other calls", GoLand's text seen live); on a pointer
 * receiver the new pointer is seen by the callees only (GoLand's description). Writes to fields (`c.name = v`) are not reported, as in
 * GoLand.
 */
class GoAssignmentToReceiverInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val targets = when (element) {
            is GoAssignmentStatement -> element.leftHandExprList.expressionList
            is GoIncDecStatement -> element.leftHandExprList.expressionList
            else -> return
        }
        val method = PsiTreeUtil.getParentOfType(element, GoMethodDeclaration::class.java) ?: return
        val receiver = method.receiver ?: return
        val name = receiver.identifier?.text?.takeIf { it != "_" } ?: return
        val semantic = GoSemanticService.getInstance(file.project)
        val message = if (method.isPointerReceiver) "Assignment to the method receiver propagates only to callees but not to callers"
        else "Assignment to the method receiver doesn't propagate to other calls"
        for (target in targets) {
            val t = GoLintPsi.unparen(target) as? GoReferenceExpression ?: continue
            if (t.expression == null && t.identifier?.text == name && semantic.resolve(t).any { it == receiver }) holder.registerProblem(t, message)
        }
    }
}

