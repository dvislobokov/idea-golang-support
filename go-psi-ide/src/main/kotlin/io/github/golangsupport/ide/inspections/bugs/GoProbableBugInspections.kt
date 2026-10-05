package io.github.golangsupport.ide.inspections.bugs

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoEditFix
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.inspections.GoInspectionText
import io.github.golangsupport.ide.inspections.GoRenameVariableFix
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
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
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoStructType

/**
 * GoLand's "Defer/go statement calls 'recover' or 'panic' directly" (`GoDeferGo`): `defer recover()` does not stop a panic (recover
 * works only when called by the deferred function itself), `defer panic(x)` / `go panic(x)` panic later than they read. Fix: wrap the
 * call in a function literal, `defer func() { recover() }()`.
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
        val message = if (name == "recover") "'recover()' is called directly by '$keyword' and does not stop a panic"
        else "'panic()' is called directly by '$keyword'"
        holder.registerProblem(call, message, WRAP)
    }

    private companion object {
        val WRAP = GoEditFix("Wrap in a function literal") { call ->
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
        holder.registerProblem(element.nameIdentifier ?: element, "$kind '$name' collides with the builtin $what", GoRenameVariableFix("Rename"))
    }
}

/**
 * GoLand's "Irregular usage of 'iota'" (`GoIrregularIota`):
 * - `iota` in a constant declaration without parentheses (`const x = iota`): it is always 0 there. Fix: replace with `0`;
 * - a spec of a `const (...)` group that repeats the type and the `iota` expression of the previous explicit spec (`B = iota` after
 *   `A = iota`): the implicit repetition already does it. Fix: remove the repeated expression.
 */
class GoIrregularIotaInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoConstSpec) return
        val declaration = element.parent as? GoConstDeclaration ?: return
        val iotas = element.expressionList.flatMap(::iotaRefs)
        if (iotas.isEmpty()) return
        if (declaration.lparen == null) {
            for (ref in iotas) holder.registerProblem(ref, "'iota' in a single constant declaration is always 0", REPLACE_WITH_ZERO)
            return
        }
        val previous = declaration.constSpecList.takeWhile { it !== element }.lastOrNull { it.expressionList.isNotEmpty() } ?: return
        if (previous.constDefinitionList.size != element.constDefinitionList.size) return
        if (norm(previous.type?.text) != norm(element.type?.text) || norm(previous.expressionList.joinToString(",") { it.text }) != norm(element.expressionList.joinToString(",") { it.text })) return
        val first = element.type ?: element.assign ?: return
        holder.registerProblem(element, "Redundant repetition of the previous constant expression with 'iota'", ProblemHighlightType.LIKE_UNUSED_SYMBOL,
            com.intellij.openapi.util.TextRange(first.startOffsetInParent, element.textLength), REMOVE_REPETITION)
    }

    companion object {
        fun iotaRefs(e: GoExpression): List<GoReferenceExpression> =
            PsiTreeUtil.findChildrenOfType(e, GoReferenceExpression::class.java).plus(listOfNotNull(e as? GoReferenceExpression))
                .filter { it.expression == null && it.identifier?.text == "iota" && GoSemanticService.getInstance(it.project).resolve(it).any { t -> GoLintPsi.isBuiltin(t) } }

        private fun norm(s: String?): String = s?.filterNot { it.isWhitespace() } ?: ""

        private val REPLACE_WITH_ZERO = GoEditFix("Replace with 0") { listOf(GoEditPlan.Edit(it.textRange.startOffset, it.textRange.endOffset, "0")) }

        private val REMOVE_REPETITION = GoEditFix("Remove the repeated expression") { spec ->
            val s = spec as? GoConstSpec ?: return@GoEditFix null
            val last = s.constDefinitionList.lastOrNull() ?: return@GoEditFix null
            listOf(GoEditPlan.Edit(last.textRange.endOffset, s.textRange.endOffset, ""))
        }
    }
}

/**
 * GoLand's "Mixed value and pointer receivers" (`GoMixedReceiverTypes`): methods of one type declared with both value and pointer
 * receivers across the package (the files of the directory with the same package clause, read through stubs). The receivers of the
 * minority kind are reported (value receivers on a tie). Fixes: change the receiver to a pointer / to a value.
 */
class GoMixedReceiverTypesInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoMethodDeclaration) return
        val typeName = element.receiverTypeName ?: return
        val receiverType = element.receiver?.type ?: return
        val (pointers, values) = receiverCounts(file)[typeName] ?: return
        if (pointers == 0 || values == 0) return
        val pointer = element.isPointerReceiver
        val minority = if (pointer) pointers < values else values <= pointers
        if (!minority) return
        holder.registerProblem(receiverType, "Methods of '$typeName' have both value and pointer receivers", if (pointer) TO_VALUE else TO_POINTER)
    }

    /** Receiver type name -> (pointer receivers, value receivers) over the package of [file]; cached until the package changes out of block. */
    private fun receiverCounts(file: GoFile): Map<String, Pair<Int, Int>> = CachedValuesManager.getCachedValue(file, COUNTS) {
        val dir = file.originalFile.containingDirectory
        val name = file.packageName
        val files = dir?.files?.filterIsInstance<GoFile>()?.filter { it.packageName == name && it.virtualFile != file.originalFile.virtualFile }.orEmpty() + file
        val counts = files.flatMap { it.methods }.mapNotNull { m -> m.receiverTypeName?.let { it to m.isPointerReceiver } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, kinds) -> kinds.count { it } to kinds.count { !it } }
        val trackers = dir?.virtualFile?.let { arrayOf<Any>(GoTrackers.getInstance(file.project).forPackage(it)) } ?: arrayOf<Any>(file)
        CachedValueProvider.Result.create(counts, *trackers, file)
    }

    private companion object {
        val COUNTS: Key<CachedValue<Map<String, Pair<Int, Int>>>> = Key.create("go.g7.receiverCounts")

        val TO_POINTER = GoEditFix("Change receiver to pointer") { type -> listOf(GoEditPlan.Edit(type.textRange.startOffset, type.textRange.startOffset, "*")) }
        val TO_VALUE = GoEditFix("Change receiver to value") { type ->
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
 * GoLand's "Assignment to a receiver" (`GoAssignmentToReceiver`, group Control flow issues):
 * - `c = …`, `c++`, `c += …` on a receiver: only the method's copy changes, callers keep theirs;
 * - `c.X = …` (through value fields only) on a value receiver whose method never uses the receiver as a whole (`return c`, `f(c)`,
 *   `&c`): the change is lost when the method returns. Fix: change the receiver to a pointer.
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
        for (target in targets) {
            val t = GoLintPsi.unparen(target) ?: continue
            if (t is GoReferenceExpression && t.expression == null) {
                if (isReceiver(t, name, receiver, semantic)) holder.registerProblem(t, "Assignment to method receiver '$name' does not propagate to callers")
                continue
            }
            if (method.isPointerReceiver) continue
            val root = fieldChainRoot(t, semantic) ?: continue
            if (!isReceiver(root, name, receiver, semantic) || usedAsWhole(method, name, receiver, semantic)) continue
            holder.registerProblem(t, "Assignment to a field of value receiver '$name' is lost when the method returns", TO_POINTER)
        }
    }

    private fun isReceiver(ref: GoReferenceExpression, name: String, receiver: GoReceiver, semantic: GoSemanticService): Boolean =
        ref.identifier?.text == name && semantic.resolve(ref).any { it == receiver }

    /** The receiver-side root `c` of `c.a.b` when every step selects a field of a struct value (not through a pointer); else null. */
    private fun fieldChainRoot(e: GoExpression, semantic: GoSemanticService): GoReferenceExpression? {
        var current: GoExpression = e
        var steps = 0
        while (current is GoReferenceExpression && current.expression != null) {
            val qualifier = GoLintPsi.unparen(current.expression) ?: return null
            val type = semantic.typeOf(qualifier)
            if (type is GoPointerType || type.underlying() !is GoStructType) return null
            current = qualifier
            steps++
        }
        return (current as? GoReferenceExpression)?.takeIf { steps > 0 && it.expression == null }
    }

    /** Whether the receiver appears in [method] other than as the qualifier of a selector. */
    private fun usedAsWhole(method: GoMethodDeclaration, name: String, receiver: GoReceiver, semantic: GoSemanticService): Boolean {
        val body = method.block ?: return false
        return PsiTreeUtil.findChildrenOfType(body, GoReferenceExpression::class.java).any { ref ->
            ref.expression == null && ref.identifier?.text == name && (ref.parent as? GoReferenceExpression)?.expression !== ref && isReceiver(ref, name, receiver, semantic)
        }
    }

    private companion object {
        val TO_POINTER = GoEditFix("Change receiver to pointer") { e ->
            val method = PsiTreeUtil.getParentOfType(e, GoMethodDeclaration::class.java) ?: return@GoEditFix null
            val type = method.receiver?.type ?: return@GoEditFix null
            if (type.text.startsWith("*")) return@GoEditFix null
            listOf(GoEditPlan.Edit(type.textRange.startOffset, type.textRange.startOffset, "*"))
        }
    }
}
