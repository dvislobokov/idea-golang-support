package io.github.golangsupport.semantic.check

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoSendStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoContinueStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoTypeSwitchGuard
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoFallthroughStatement
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoValue
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.impl.GoTypeReferenceExpressionMixin
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.infer.GoInference
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.indices
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.psi.GoPsiUtil.literalType
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.psi.GoPsiUtil.types
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.*
import io.github.golangsupport.semantic.types.GoTypePredicates.assignable
import io.github.golangsupport.semantic.types.GoTypePredicates.comparable
import io.github.golangsupport.semantic.types.GoTypePredicates.identical
import io.github.golangsupport.semantic.types.GoTypePredicates.isKnown
import io.github.golangsupport.semantic.types.GoTypePredicates.isUntyped
import io.github.golangsupport.lang.psi.GoType as PsiType
import org.jetbrains.annotations.ApiStatus
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A conservative port of `go/types` checks producing [GoDiagnostic]s for one file. Every check
 * only fires when the involved types are fully known, so unresolved dependencies never cascade
 * into false reports. Messages follow go/types wording (the testdata harness matches substrings).
 */
@ApiStatus.Internal
class GoChecker(private val project: Project, private val file: GoFile) {
    private val typer = GoExpressionTyper.getInstance(project)
    private val resolver = GoResolver.getInstance(project)
    private val packages = GoPackageModel.getInstance(project)
    private val diagnostics = ArrayList<GoDiagnostic>()
    private val usedImports = HashSet<GoImportSpec>()
    private val usedLocals = HashSet<PsiElement>()
    private val usedLabels = HashSet<GoLabelDefinition>()
    /** Valid-looking expression statements reported as "not used" only when nothing inside them was reported (go/types reports unused values of valid operands only). */
    private val pendingUnused = ArrayList<GoExpression>()
    private val isCgo = file.isCgo

    internal companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(GoChecker::class.java)
        /** go/types `prec`: untyped integer constants are limited to 512 bits. */
        private const val UNTYPED_INT_PRECISION = 512
        /** go/types maxTermCount. */
        private const val MAX_UNION_TERMS = 100

        /** Classes never dropped by the containment filter. */
        private val KEEP_ALWAYS = setOf("unused-import", "unused-variable", "unused-label", "redeclared", "unused-value", "not-constant", "missing-return", "init-cycle")

        /** Classes that never cause an enclosing diagnostic to be dropped. */
        private val NEVER_INNER = setOf("unused-variable", "unused-label", "map-key", "misplaced-constraint")

        /** Report order of the final list: by start offset, enclosing ranges first (a stable sort). */
        val ORDER: Comparator<GoDiagnostic> = compareBy({ it.range.startOffset }, { -it.range.endOffset })

        /** Whether [d] is never dropped by the containment filter. */
        fun keptAlways(d: GoDiagnostic): Boolean = d.code in KEEP_ALWAYS

        /** Whether [d] is dropped because of [o]: [d] strictly contains [o] and [o] counts as an inner error. */
        fun strictlyContains(d: GoDiagnostic, o: GoDiagnostic): Boolean =
            o !== d && d.range.contains(o.range) && d.range != o.range && o.code !in NEVER_INNER

        /**
         * go/types marks an operand invalid after its first error and reports nothing about the
         * enclosing expressions: diagnostics that strictly contain another one are dropped. The
         * result is sorted by [ORDER].
         */
        fun dropContaining(diagnostics: List<GoDiagnostic>): List<GoDiagnostic> {
            val sorted = diagnostics.sortedWith(ORDER)
            return sorted.filter { d -> keptAlways(d) || sorted.none { o -> strictlyContains(d, o) } }
        }

        /** `"p" imported and not used` for every import of [file] not in [used] (blank, dot and `"C"` imports never). */
        fun unusedImports(file: GoFile, used: Set<GoImportSpec>): List<GoDiagnostic> {
            val out = ArrayList<GoDiagnostic>()
            for (spec in file.imports) {
                if (spec.isBlank || spec.isDot || spec.path == "C") continue
                if (spec in used) continue
                val alias = spec.alias
                out += GoDiagnostic(spec.textRange, if (alias != null) "\"${spec.path}\" imported as $alias and not used" else "\"${spec.path}\" imported and not used", "unused-import")
            }
            return out
        }
    }

    private val terminating = GoTerminating { call -> GoTerminating.isPanicCallee(call, ::isBuiltinCallee) }

    /** Which part of the file this instance checks (see [GoIncrementalChecker]). */
    private enum class Mode { FILE, PACKAGE_LEVEL, BODY }

    private var mode = Mode.FILE

    /** Diagnostics reported at the file element (package-level redeclarations): the first ones of a walk over the file. */
    private var fileLevelCount = 0

    /**
     * The whole file in one walk (the reference implementation). `GoSemanticService.check(file)`
     * assembles the same list from cached per-body results ([GoIncrementalChecker]); this entry
     * stays for the equivalence tests and as a fallback (`-Dgopsi.check.monolithic=true`).
     */
    fun checkMonolithic(): List<GoDiagnostic> {
        startOnce()
        file.accept(walker(null))
        diagnostics += unusedImports(file, usedImports)
        checkUnusedLocals(file)
        checkLabels(file)
        reportPendingUnused()
        return dropContaining(diagnostics)
    }

    /**
     * Everything outside function bodies: declarations, signatures, type specs, const/var specs
     * and their initializers, package-level redeclarations. Not included: init cycles
     * ([checkInitCycleUnit]), unused imports (they need every body), missing returns and
     * everything inside bodies ([checkBody]). The outermost bodies met by the walk (top-level
     * function and method bodies, bodies of function literals in package-level code) are returned
     * in document order without being entered.
     */
    internal fun checkPackageLevel(): PackageLevelPass {
        startOnce()
        mode = Mode.PACKAGE_LEVEL
        val bodies = ArrayList<GoBlock>()
        file.accept(walker(bodies))
        return PackageLevelPass(diagnostics.toList(), fileLevelCount, usedImports.toSet(), bodies)
    }

    /**
     * One outermost body [body] ([GoPsiUtil.outermostBody]; function literals inside it included):
     * the missing return of its function, every check inside it, unused variables and labels,
     * unused values, and the containment filter among its own diagnostics.
     */
    internal fun checkBody(body: GoBlock): BodyPass {
        startOnce()
        mode = Mode.BODY
        when (val owner = body.parent) {
            is GoFunctionDeclaration -> safely { checkMissingReturn(owner.signature, body) }
            is GoMethodDeclaration -> safely { checkMissingReturn(owner.signature, body) }
            is GoFunctionLit -> safely { checkMissingReturn(owner.signature, body) }
        }
        body.accept(walker(null))
        checkUnusedLocals(body)
        checkLabels(body)
        reportPendingUnused()
        return BodyPass(dropContaining(diagnostics), usedImports.toSet())
    }

    /** Initialization cycles of the file, and the function bodies the dependency walk followed. */
    internal fun checkInitCycleUnit(): Pair<List<GoDiagnostic>, Set<GoBlock>> {
        startOnce()
        val bodies = HashSet<GoBlock>()
        safely { checkInitCycles(file, bodies) }
        return diagnostics.toList() to bodies
    }

    private var started = false

    private fun startOnce() {
        check(!started) { "a GoChecker instance checks once" }
        started = true
    }

    /** A walk reporting every element; with [bodies], outermost bodies are collected there and not entered. */
    private fun walker(bodies: MutableList<GoBlock>?) = object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            if (bodies != null && element is GoBlock && isOwnBody(element)) {
                bodies += element
                return
            }
            safely { checkElement(element) }
            safely { checkElementMore(element) }
            if (element is GoFile) fileLevelCount = diagnostics.size
            super.visitElement(element)
        }
    }

    /** A function, method or function literal body (outside bodies every such block is an outermost body). */
    private fun isOwnBody(block: GoBlock): Boolean = block.parent.let { it is GoFunctionOrMethodDeclaration || it is GoFunctionLit }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            // Never let one broken construct hide the rest of the file.
            LOG.debug("check", e)
        } catch (e: LinkageError) {
            // Seen only under the test JVM's instrumentation agent (string-concat bootstrap failures); skip the element.
            LOG.debug("check", e)
        } catch (e: StackOverflowError) {
            // Pathologically deep expressions (generated tables): the element is skipped, the rest of the file is checked.
            LOG.debug("check", e)
        }
    }

    /** go/types reports unused values of valid operands only: a candidate is reported when nothing inside it was. */
    private fun reportPendingUnused() {
        for (x in pendingUnused) {
            val range = x.textRange
            if (diagnostics.none { range.contains(it.range) }) report(x, "${describe(x)} is not used", "unused-value")
        }
    }

    /** Result of [checkPackageLevel]: [diagnostics] in report order, the first [fileLevelCount] of them reported at the file element. */
    internal class PackageLevelPass(val diagnostics: List<GoDiagnostic>, val fileLevelCount: Int, val usedImports: Set<GoImportSpec>, val bodies: List<GoBlock>)

    /** Result of [checkBody]: the diagnostics kept by the containment filter among the body's own ones (sorted by [ORDER]), the imports used. */
    internal class BodyPass(val kept: List<GoDiagnostic>, val usedImports: Set<GoImportSpec>)

    // --- reporting ---

    private fun report(element: PsiElement, message: String, code: String) {
        diagnostics += GoDiagnostic(element.textRange, message, code)
    }

    private fun report(range: TextRange, message: String, code: String) {
        diagnostics += GoDiagnostic(range, message, code)
    }

    private val myPkgPath: String? by lazy { packages.packagePathOf(file) }

    /** Named types of other packages are qualified with their package name, as go/types does relative to the current package. */
    private fun render(t: GoType): String = GoTypeRenderer.render(t) { named ->
        val p = named.pkgPath
        if (p == null || p == myPkgPath || GoUniverse.isBuiltinDeclaration(named.declaration)) null else p.substringAfterLast('/')
    }

    // --- dispatch ---

    private fun checkElement(e: PsiElement) {
        when (e) {
            is GoReferenceExpression -> checkReference(e)
            is GoTypeReferenceExpression -> checkTypeReference(e)
            is GoKey -> checkKey(e)
            is GoVarSpec -> checkVarSpec(e)
            is GoConstSpec -> checkConstSpec(e)
            is GoShortVarDeclaration -> checkShortVarDecl(e)
            is GoAssignmentStatement -> checkAssignment(e)
            is GoReturnStatement -> checkReturn(e)
            is GoCallExpr -> checkCall(e)
            is GoConversionExpr -> checkConversionExpr(e)
            is GoBinaryExpr -> checkBinary(e)
            is GoUnaryExpr -> checkUnary(e)
            is GoIndexOrSliceExpr -> checkIndex(e)
            is GoTypeAssertionExpr -> checkTypeAssertion(e)
            is GoSendStatement -> checkSend(e)
            is GoIncDecStatement -> checkIncDec(e)
            is GoSimpleStatement -> checkExpressionStatement(e)
            is GoCompositeLit -> checkCompositeLit(e)
            is GoBlock -> checkRedeclarations(e)
            is GoFile -> { checkPackageRedeclarations(e); if (mode == Mode.FILE) checkInitCycles(e, null) }
            is GoBreakStatement -> checkBreak(e)
            is GoContinueStatement -> checkContinue(e)
            is GoIfStatement -> e.condition?.let { checkCondition(it, "if statement") }
            is GoForStatement -> { e.condition?.let { checkCondition(it, "for statement") }; e.rangeClause?.let(::checkRange); checkForClause(e) }
            is GoDeferStatement -> e.expression?.let { checkDeferGo(it, "defer") }
            is GoGoStatement -> e.expression?.let { checkDeferGo(it, "go") }
            is GoTypeSwitchGuard -> checkTypeSwitchGuard(e)
            is GoExprSwitchStatement -> checkExprSwitch(e)
            is GoTypeSwitchStatement -> checkTypeSwitch(e)
            is GoSelectStatement -> checkSelect(e)
            is GoFallthroughStatement -> checkFallthrough(e)
            // In the package-level pass the missing return of an outermost body belongs to its body pass.
            is GoFunctionDeclaration -> { if (mode == Mode.FILE) checkMissingReturn(e.signature, e.block); checkInitSignature(e) }
            is GoMethodDeclaration -> if (mode == Mode.FILE) checkMissingReturn(e.signature, e.block)
            is GoFunctionLit -> if (mode != Mode.PACKAGE_LEVEL) checkMissingReturn(e.signature, GoPsiUtil.run { e.block })
            is GoTypeSpec -> checkTypeSpec(e)
            is GoReceiver -> checkReceiver(e)
            is io.github.golangsupport.lang.psi.GoGotoStatement -> e.labelRef?.takeIf { it.text == "_" }?.let { report(it, "label _ not declared", "label") }
            is GoAnonymousFieldDefinition -> checkEmbeddedField(e)
            is PsiType -> checkTypeNode(e)
            is io.github.golangsupport.lang.psi.GoConstraintElem -> checkUnion(e)
            is GoLabelRef -> GoScopes.resolveLabel(e, e.identifier?.text ?: "")?.let { usedLabels += it }
        }
    }

    // --- control flow ---

    /** Spec "Terminating statements": a function with results must not fall off the end of its body. */
    private fun checkMissingReturn(signature: io.github.golangsupport.lang.psi.GoSignature?, body: GoBlock?) {
        body ?: return
        val result = signature?.result ?: return
        val hasResults = result.type != null || result.parameters?.parameterDeclarationList?.isNotEmpty() == true
        if (!hasResults) return
        if (terminating.isTerminatingList(body.statementList)) return
        report(body.rbrace ?: body, "missing return", "missing-return")
    }

    private fun checkFallthrough(stmt: GoFallthroughStatement) {
        // The statement, possibly wrapped in labels, must be the last statement of an expression switch case that is not the last clause.
        var s: PsiElement = stmt
        while (s.parent is GoLabeledStatement) s = s.parent
        val clause = s.parent
        val stmts = when (clause) { is io.github.golangsupport.lang.psi.GoExprCaseClause -> clause.statementList; is io.github.golangsupport.lang.psi.GoTypeCaseClause -> clause.statementList; else -> null }
        if (stmts == null || stmts.lastOrNull() !== s) { report(stmt, "fallthrough statement out of place", "fallthrough"); return }
        if (clause is io.github.golangsupport.lang.psi.GoTypeCaseClause) { report(stmt, "cannot fallthrough in type switch", "fallthrough"); return }
        val switch = clause.parent as? GoExprSwitchStatement ?: return
        if (switch.exprCaseClauseList.lastOrNull() === clause) report(stmt, "cannot fallthrough final case in switch", "fallthrough")
    }

    private fun checkSelect(stmt: GoSelectStatement) {
        val defaults = stmt.commClauseList.filter { it.commCase?.default != null }
        defaults.drop(1).forEach { report(it.commCase?.default ?: it, "multiple defaults in select", "duplicate-default") }
        // Each case must be a send, a receive expression, or a receive assignment (go/types `selectStmt`).
        for (clause in stmt.commClauseList) {
            val case = clause.commCase ?: continue
            if (case.default != null) continue
            val s = case.statement ?: continue
            val ok = when (s) {
                is GoSendStatement, is io.github.golangsupport.lang.psi.GoRecvStatement -> true
                is GoSimpleStatement -> s.statement?.let { it is GoSendStatement } ?: (s.expressions.singleOrNull()?.let { unparen(it) }.let { it is GoUnaryExpr && it.operator === GoTypes.ARROW })
                is GoShortVarDeclaration -> s.expressionList.singleOrNull()?.let { unparen(it) }.let { it is GoUnaryExpr && it.operator === GoTypes.ARROW }
                is GoAssignmentStatement -> s.expressionList.singleOrNull()?.let { unparen(it) }.let { it is GoUnaryExpr && it.operator === GoTypes.ARROW }
                else -> false
            }
            if (!ok) report(s, "select case must be receive, send or assign recv", "select-case")
        }
    }

    /** `for init; cond; post`: the post statement must not declare variables (go/types `forStmt`). */
    private fun checkForClause(stmt: GoForStatement) {
        val clause = stmt.forClause ?: return
        val post = clause.statementList.lastOrNull { it.textRange.startOffset > (clause.expression?.textRange?.endOffset ?: GoPsiUtil.childToken(clause, GoTypes.SEMICOLON)?.textRange?.endOffset ?: -1) } ?: return
        val decl = (post as? GoSimpleStatement)?.statement as? GoShortVarDeclaration ?: post as? GoShortVarDeclaration ?: return
        if (clause.statementList.size < 2 && clause.expression == null && GoPsiUtil.children(clause, PsiElement::class.java).count { it.node.elementType === GoTypes.SEMICOLON } < 2) return
        report(decl, "cannot declare in post statement of for loop", "for-post")
        usedLocals += decl.varDefinitionList
    }

    private fun checkTypeSwitch(stmt: GoTypeSwitchStatement) {
        val defaults = stmt.typeCaseClauseList.filter { it.default != null }
        defaults.drop(1).forEach { report(it.default ?: it, "multiple defaults in switch", "duplicate-default") }
        val guardType = GoPsiUtil.run { stmt.guard }?.expression?.let { typer.typeOf(it) }
        val iface = guardType?.takeIf { isKnown(it) }?.underlying() as? GoInterfaceType
        var seenNil = false
        val seen = ArrayList<GoType>()
        for (clause in stmt.typeCaseClauseList) for (tn in clause.types) {
            val isNil = tn.typeReferenceExpression?.identifier?.text == "nil" && tn.typeArguments == null &&
                resolver.resolveTypeReference(tn.typeReferenceExpression!!).let { it == null || it is GoNamedElement && GoUniverse.isBuiltinDeclaration(it) }
            val t = if (isNil) null else typer.builder.typeOf(tn).also { if (!isKnown(it)) return@checkTypeSwitch }
            if (t == null) { if (seenNil) report(tn, "duplicate case nil in type switch", "duplicate-case"); seenNil = true; continue }
            if (seen.any { identical(it, t) }) { report(tn, "duplicate case ${render(t)} in type switch", "duplicate-case"); continue }
            seen += t
            if (t != null && iface != null && t !is GoTypeParamType && t.underlying() !is GoInterfaceType && !GoTypePredicates.implements(t, iface)) {
                report(tn, "impossible type switch case: ${guardType.let { exprText(GoPsiUtil.run { stmt.guard }!!.expression!!) }} (variable of type ${render(guardType)}) cannot have dynamic type ${render(t)} ${implementsDetail(t, iface)}", "type-switch")
            }
        }
    }

    /**
     * Spec "Method declarations": the receiver base type must be a defined type of this package
     * whose underlying type is neither a pointer nor an interface (nor unsafe.Pointer).
     */
    private fun checkReceiver(receiver: GoReceiver) {
        val typeNode = receiver.type ?: return
        var base: PsiType = typeNode
        var explicitPointer = false
        while (true) {
            base = when (base) {
                is io.github.golangsupport.lang.psi.GoParType -> base.type ?: return
                is io.github.golangsupport.lang.psi.GoPointerType -> { if (explicitPointer) { report(base, "invalid receiver type ${exprText(typeNode)} (cannot be a pointer)", "receiver"); return }; explicitPointer = true; base.type ?: return }
                else -> break
            }
        }
        val ref = base.typeReferenceExpression ?: return
        // Receiver type parameters declare names (`func (*T[e, e]) m()` redeclares e).
        val seenParams = HashSet<String>()
        base.typeArguments?.typeList?.forEach { a ->
            val n = a.typeReferenceExpression?.takeIf { a.typeArguments == null && (it as? GoTypeReferenceExpressionMixin)?.qualifierName == null }?.identifier?.text ?: return@forEach
            if (n != "_" && !seenParams.add(n)) report(a, "$n redeclared in this block", "redeclared")
        }
        val target = resolver.resolveTypeReference(ref)
        val name = ref.text
        if (target == null) return
        if (target !is GoTypeSpec || GoUniverse.isBuiltinDeclaration(target)) {
            if (target is GoNamedElement && GoUniverse.isBuiltinDeclaration(target)) report(ref, "cannot define new methods on non-local type $name", "receiver")
            else if (target is GoTypeParamDefinition) report(ref, "cannot define new methods on non-local type $name", "receiver")
            return
        }
        if (target.containingFile?.let { f -> packages.packagePathOf(f as GoFile) } != myPkgPath) { report(ref, "cannot define new methods on non-local type $name", "receiver"); return }
        // Follow aliases to the declared type; `type A = int` has no local base.
        val t = typer.builder.typeOfDeclaration(target, ref)
        if (!isKnown(t)) return
        if (t !is GoNamedType) {
            val cause = when (val u = t.underlying()) {
                is GoPointerType -> if (u.elem.underlying() is GoInterfaceType) "cannot be a pointer to an interface" else "cannot be a pointer"
                is GoInterfaceType -> "cannot be an interface"
                else -> null
            }
            if (cause != null) report(ref, "invalid receiver type $name ($cause)", "receiver")
            else if (t == GoBasicType.UNSAFE_POINTER) report(ref, "invalid receiver type $name (cannot be unsafe.Pointer)", "receiver")
            else if (t is GoBasicType) report(ref, "cannot define new methods on non-local type ${render(t)}", "receiver")
            // go/types `validRecv`: an alias of any other unnamed type (`type A = [10]int`).
            else if (target.isAlias) report(ref, "invalid receiver type $name", "receiver")
            return
        }
        when (val u = t.underlying()) {
            is GoPointerType -> report(ref, "invalid receiver type $name (${if (u.elem.underlying() is GoInterfaceType) "cannot be a pointer to an interface" else "cannot be a pointer"})", "receiver")
            is GoInterfaceType -> report(ref, "invalid receiver type $name (cannot be an interface)", "receiver")
            is GoBasicType -> if (u.kind == GoBasicKind.UNSAFE_POINTER) report(ref, "invalid receiver type $name (cannot be unsafe.Pointer)", "receiver")
            else -> {}
        }
    }

    /** go/types `structType`: an embedded field must not be a pointer type, a pointer to an interface, unsafe.Pointer or a type parameter. */
    private fun checkEmbeddedField(field: GoAnonymousFieldDefinition) {
        val ref = field.typeReferenceExpression ?: return
        val target = resolver.resolveTypeReference(ref) ?: return
        var t = typer.builder.typeOfDeclaration(target, ref)
        if (!isKnown(t)) return
        var isPtr = field.mul != null
        // An alias of an unnamed pointer type (`type eZ = *struct{...}`) embeds the pointer's base.
        if (!isPtr && t is GoPointerType) { t = t.elem; isPtr = true }
        if (t is GoTypeParamType) { report(field, "embedded field type cannot be a (pointer to a) type parameter", "embedded-field"); return }
        when (val u = t.underlying()) {
            is GoBasicType -> if (u.kind == GoBasicKind.UNSAFE_POINTER) report(field, "embedded field type cannot be unsafe.Pointer", "embedded-field")
            is GoPointerType -> report(field, "embedded field type cannot be a pointer", "embedded-field")
            is GoInterfaceType -> if (isPtr) report(field, "embedded field type cannot be a pointer to an interface", "embedded-field")
            else -> {}
        }
    }

    /** `func init` must have no arguments, results or type parameters (spec "Package initialization"). */
    private fun checkInitSignature(decl: GoFunctionDeclaration) {
        if (decl.name != "init") return
        val sig = decl.signature ?: return
        val hasParams = sig.parameters?.parameterDeclarationList?.isNotEmpty() == true
        val hasResults = sig.result != null
        if (hasParams || hasResults) report(decl.nameIdentifier ?: decl, "func init must have no arguments and no return values", "init-signature")
        if (decl.typeParameters != null) report(decl.nameIdentifier ?: decl, "func init must have no type parameters", "init-signature")
    }

    /**
     * Spec "Package initialization" / go/types `initOrder`: a package-level variable or constant
     * whose initializer depends on itself, directly or through other package-level variables and
     * functions (function bodies are followed), is an initialization cycle. Each cycle is reported
     * once, at its first member in this file. [followedBodies] receives every outermost body the
     * dependency walk read (function bodies and function literals in initializers): the result
     * depends on them.
     */
    private fun checkInitCycles(f: GoFile, followedBodies: MutableSet<GoBlock>?) {
        val inits = LinkedHashMap<GoNamedElement, List<GoExpression>>()
        // Package-level specs only: blocks are function bodies, not entered (lazy bodies stay collapsed).
        val varSpecs = ArrayList<GoVarSpec>()
        val constSpecs = ArrayList<GoConstSpec>()
        f.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is GoBlock -> return
                    is GoVarSpec -> varSpecs += element
                    is GoConstSpec -> constSpecs += element
                }
                super.visitElement(element)
            }
        })
        for (spec in varSpecs) {
            if (GoPsiUtil.isInsideFunctionBody(spec)) continue
            val values = spec.expressionList
            spec.varDefinitionList.forEachIndexed { i, d -> inits[d] = if (values.size == spec.varDefinitionList.size) listOfNotNull(values.getOrNull(i)) else values }
        }
        for (spec in constSpecs) {
            if (GoPsiUtil.isInsideFunctionBody(spec)) continue
            val values = spec.expressionList
            spec.constDefinitionList.forEachIndexed { i, d -> inits[d] = if (values.size == spec.constDefinitionList.size) listOfNotNull(values.getOrNull(i)) else values }
        }
        if (inits.isEmpty()) return
        // Function literals in initializers are bodies of their own (read by the walk below).
        if (followedBodies != null) for (values in inits.values) for (v in values) {
            for (lit in listOfNotNull(v as? GoFunctionLit) + PsiTreeUtil.findChildrenOfType(v, GoFunctionLit::class.java)) {
                GoPsiUtil.run { lit.block }?.let(GoPsiUtil::outermostBody)?.let(followedBodies::add)
            }
        }
        val funcDeps = HashMap<GoFunctionOrMethodDeclaration, Set<GoNamedElement>>()
        fun refsIn(root: PsiElement, out: MutableSet<GoNamedElement>, visited: MutableSet<GoFunctionOrMethodDeclaration>) {
            for (ref in listOfNotNull(root as? GoReferenceExpression) + PsiTreeUtil.findChildrenOfType(root, GoReferenceExpression::class.java)) {
                if (ref.qualifier != null || ref.parent is GoKey) continue
                val target = resolver.resolveReferenceExpression(ref).firstOrNull()?.element ?: continue
                if (target is GoVarDefinition || target is io.github.golangsupport.lang.psi.GoConstDefinition) { if (!GoPsiUtil.isInsideFunctionBody(target) && target.containingFile == f) out += target as GoNamedElement }
                else if (target is GoFunctionDeclaration && target.containingFile == f && visited.add(target)) {
                    val body = target.block ?: continue
                    followedBodies?.add(body)
                    out += funcDeps.getOrPut(target) { val s = HashSet<GoNamedElement>(); refsIn(body, s, visited); s }
                }
            }
        }
        val deps = HashMap<GoNamedElement, Set<GoNamedElement>>()
        for ((d, values) in inits) {
            val s = HashSet<GoNamedElement>()
            for (v in values) refsIn(v, s, HashSet())
            deps[d] = s
        }
        // Report each elementary cycle once, at its lowest-offset member.
        val reported = HashSet<GoNamedElement>()
        val color = HashMap<GoNamedElement, Int>() // 1 = on path, 2 = done
        val path = ArrayList<GoNamedElement>()
        fun dfs(n: GoNamedElement) {
            color[n] = 1; path += n
            for (m in deps[n].orEmpty()) {
                when (color[m]) {
                    1 -> {
                        val cycle = path.subList(path.indexOf(m), path.size)
                        val first = cycle.minByOrNull { it.textOffset } ?: continue
                        if (reported.add(first)) {
                            val chain = cycle.drop(cycle.indexOf(first)) + cycle.take(cycle.indexOf(first))
                            val detail = (chain + first).zipWithNext().joinToString("") { (a, b) -> "\n\t${a.name} refers to ${b.name}" }
                            report(first.nameIdentifier ?: first, "initialization cycle for ${first.name}$detail", "init-cycle")
                        }
                    }
                    null -> dfs(m)
                    else -> {}
                }
            }
            path.removeAt(path.size - 1); color[n] = 2
        }
        for (d in inits.keys) if (color[d] == null) dfs(d)
    }

    /** go/types `validCycle`: a type that contains itself without indirection (alias cycles, direct struct/array embedding). */
    private fun checkTypeSpec(spec: GoTypeSpec) {
        // The universe declarations of builtin/builtin.go (`type bool bool`) are not recursive.
        if (GoUniverse.isBuiltinDeclaration(spec)) return
        // go/types typeDecl (go.dev/issue/45639): `type T[P any] P` is not permitted.
        val rhs = spec.type
        if (!spec.isAlias && rhs != null && typer.builder.typeOf(rhs) is GoTypeParamType) {
            report(rhs, "cannot use a type parameter as RHS in type declaration", "misplaced-type-param")
            return
        }
        val name = spec.name ?: return
        if (name == "_") return
        val path = ArrayList<GoTypeSpec>()
        if (refersToItself(spec, spec, HashSet(), path)) {
            // go/types reports a cycle once, at its first declaration in source order.
            if (path.any { it.containingFile == spec.containingFile && it.textOffset < spec.textOffset }) return
            // go/types cycleError: `T refers to itself` for a one-element cycle, else one `A refers to B` line per edge.
            // A generic type refers to itself through its instance with its own parameters (`irGen[A]`, seen live in the compiler).
            val self = spec.typeParameters?.let { tp -> GoScopes.typeParamDefinitions(tp).joinToString(", ", "$name[", "]") { it.name ?: "_" } } ?: name
            val message = if (path.size == 1) "invalid recursive type: $self refers to itself"
            else "invalid recursive type $name" + (listOf(spec) + path).zipWithNext().joinToString("") { (a, b) -> "\n\t${a.name} refers to ${b.name}" }
            report(spec.nameIdentifier ?: spec, message, "recursive-type")
        }
    }

    private fun refersToItself(root: GoTypeSpec, spec: GoTypeSpec, visited: MutableSet<GoTypeSpec>, path: MutableList<GoTypeSpec>): Boolean {
        val typeNode = spec.type ?: return false
        return directTypeReferences(typeNode).any { ref ->
            val target = resolver.resolveTypeReference(ref) as? GoTypeSpec ?: return@any false
            if (target === root) { path += target; return@any true }
            // A cycle returns to this package: declarations of other packages (no import cycles) cannot lead back.
            if (target.containingFile?.containingDirectory != root.containingFile?.containingDirectory) return@any false
            if (!visited.add(target)) return@any false
            path += target
            if (refersToItself(root, target, visited, path)) true else { path.removeAt(path.size - 1); false }
        }
    }

    /** Type references reached from [node] without indirection: through aliases, arrays, struct fields and parentheses. */
    private fun directTypeReferences(node: PsiElement): List<GoTypeReferenceExpression> {
        val out = ArrayList<GoTypeReferenceExpression>()
        fun walk(e: PsiElement?) {
            when (e) {
                null -> {}
                is GoTypeReferenceExpression -> out += e
                is io.github.golangsupport.lang.psi.GoPointerType, is io.github.golangsupport.lang.psi.GoMapType, is io.github.golangsupport.lang.psi.GoChannelType,
                is io.github.golangsupport.lang.psi.GoFunctionType -> {}
                // go/types validType: embedded interface elements (single non-tilde terms; unions are not followed).
                is io.github.golangsupport.lang.psi.GoInterfaceType -> e.constraintElemList.forEach { c ->
                    c.constraintTermList.singleOrNull()?.takeIf { it.tilde == null }?.let { walk(it.type) }
                }
                is io.github.golangsupport.lang.psi.GoArrayOrSliceType -> if (GoPsiUtil.children(e, GoExpression::class.java).isNotEmpty() || e.ellipsis != null) walk(e.type)
                is io.github.golangsupport.lang.psi.GoStructType -> e.fieldDeclarationList.forEach { f -> walk(f.type); f.anonymousFieldDefinition?.let { if (it.mul == null) walk(it.typeReferenceExpression) } }
                is PsiType -> { walk(e.typeReferenceExpression); e.children.forEach { c -> if (c is PsiType) walk(c) } }
                else -> {}
            }
        }
        walk(node)
        return out
    }

    // --- references ---

    private fun checkReference(ref: GoReferenceExpression) {
        val name = ref.referenceName ?: return
        val qualifier = ref.qualifier
        if (ref.parent is GoKey && qualifier == null) {
            val key = ref.parent as GoKey
            if (resolver.isFieldKey(key)) return
            val lt = resolver.literalTypeOfKey(key)
            if (lt == null || !isKnown(lt)) return
        }
        if (name == "_" && qualifier == null) {
            if (!isAssignmentTarget(ref)) report(ref, "cannot use _ as value", "blank-value")
            return
        }
        // go/types `nonGeneric` on the operand of a selector: `T.m` with generic T.
        if (qualifier is GoExpression) bareGenericType(qualifier)?.let { report(qualifier, "cannot use generic type ${it.name} without instantiation", "generic-no-instantiation"); return }
        val results = resolver.resolveReferenceExpression(ref)
        if (name == "iota" && qualifier == null && results.all { it.element is GoNamedElement && GoUniverse.isBuiltinDeclaration(it.element as GoNamedElement) }) {
            if (!isIotaInScope(ref)) report(ref, "cannot use iota outside constant declaration", "iota")
            return
        }
        // go/types: a built-in function is not a value (`_ = println`, `new(len)`).
        if (qualifier == null && name in GoUniverse.FUNCTIONS && (results.isEmpty() || results.all { (it.element as? GoNamedElement)?.let(GoUniverse::isBuiltinDeclaration) == true })) {
            var callee: PsiElement = ref
            while (callee.parent is io.github.golangsupport.lang.psi.GoParenthesesExpr) callee = callee.parent
            val call = callee.parent as? GoCallExpr
            if (call == null || call.expression !== callee) {
                report(ref, "$name (built-in) must be called", "builtin-value")
                return
            }
        }
        if (results.isNotEmpty()) {
            for (r in results) {
                when (r) {
                    is GoResolver.Result.Import -> usedImports += r.element
                    is GoResolver.Result.Declaration -> if (!isAssignmentTarget(ref)) usedLocals += r.element
                    is GoResolver.Result.Cgo -> usedImports += r.element
                    is GoResolver.Result.Selection -> {
                        val sel = r.selection
                        if (sel is GoLookup.Selection.Ambiguous) report(ref.identifier ?: ref, "ambiguous selector ${exprText(ref)}", "ambiguous-selector")
                        else if (sel is GoLookup.Selection.Field && qualifier is GoExpression && ref.parent !is GoKey && typer.isTypeExpression(qualifier))
                            report(ref.identifier ?: ref, "operand for field selector ${sel.member.name} must be value of type ${render(typer.typeOf(qualifier))}", "field-selector")
                        else if (sel is GoLookup.Selection.Method && qualifier != null && sel.method.pointerReceiver && typer.isTypeExpression(qualifier)) {
                            val qt = typer.typeOf(qualifier)
                            val viaPointer = sel.path.any { it.embedded && it.type is GoPointerType }
                            if (!viaPointer && qt !is GoPointerType && qt.underlying() !is GoInterfaceType && qt.underlying() !is GoPointerType) report(ref, "invalid method expression ${exprText(ref)} (needs pointer receiver (*${render(qt)}).${sel.method.name})", "method-expression")
                        }
                        else if (sel is GoLookup.Selection.Method && qualifier is GoExpression && sel.method.pointerReceiver && !sel.indirect && sel.path.none { it.embedded && it.type is GoPointerType }) {
                            // go/types: a pointer method of a non-addressable value (`T{}.p`) cannot be selected.
                            val qt = typer.typeOf(qualifier)
                            if (isKnown(qt) && qt !is GoTypeParamType && qt.underlying() !is GoPointerType && qt.underlying() !is GoInterfaceType && !isAddressable(qualifier))
                                report(ref.identifier ?: ref, "cannot call pointer method ${sel.method.name} on ${render(qt)}", "pointer-method")
                        }
                    }
                    else -> {}
                }
            }
            return
        }
        if (qualifier == null) {
            if (name == "iota") {
                if (!isInsideConstSpec(ref)) report(ref, "cannot use iota outside constant declaration", "iota")
                return
            }
            if (GoUniverse.isBuiltin(name)) return
            report(ref.identifier ?: ref, "undefined: $name", "undefined")
            return
        }
        // pkg.Name
        if (qualifier is GoReferenceExpression && qualifier.qualifier == null) {
            val q = resolver.resolveReferenceExpression(qualifier)
            val import = q.firstOrNull { it is GoResolver.Result.Import } as? GoResolver.Result.Import
            if (import != null) {
                usedImports += import.element
                if (import.element.path == "C") return
                val pkg = packages.resolveImport(import.element.path, file) ?: return // missing dependency: not our error
                val unexported = packages.scopeOf(pkg).lookup(name).isNotEmpty()
                if (unexported) report(ref.identifier ?: ref, "name $name not exported by package ${pkg.name}", "unexported")
                else report(ref.identifier ?: ref, "undefined: ${qualifier.text}.$name", "undefined")
                return
            }
            if (q.any { it is GoResolver.Result.Cgo }) return
        }
        val qt = typer.typeOf(qualifier)
        if (qt is GoUnknownType || !isKnown(qt)) return
        if (qt is GoTupleType) return
        val text = exprText(ref)
        val pointerToInterface = (qt is GoPointerType && qt.elem.underlying() is GoInterfaceType) || (qt !is GoPointerType && (qt.underlying() as? GoPointerType)?.elem?.underlying() is GoInterfaceType)
        if (pointerToInterface) {
            report(ref.identifier ?: ref, "$text undefined (type ${render(qt)} is pointer to interface, not interface)", "undefined-member")
            return
        }

        report(ref.identifier ?: ref, "$text undefined (${lookupError(qt, name, false)})", "undefined-member")
    }

    /** `iota` is only defined inside the expressions of a const spec (not inside a function literal within it). */
    private fun isInsideConstSpec(e: PsiElement): Boolean {
        var x: PsiElement? = e.parent
        while (x != null && x !is GoFile) {
            if (x is GoConstSpec) return true
            if (x is GoFunctionLit || x is GoFunctionOrMethodDeclaration) return false
            x = x.parent
        }
        return false
    }

    /** `iota` is usable anywhere inside a constant declaration, including function literals in its initializer (#22345). */
    private fun isIotaInScope(e: PsiElement): Boolean {
        var x: PsiElement? = e.parent
        while (x != null && x !is GoFile) {
            if (x is GoConstSpec) return true
            if (x is GoFunctionOrMethodDeclaration) return false
            x = x.parent
        }
        return false
    }

    private fun checkTypeReference(ref: GoTypeReferenceExpression) {
        val mixin = ref as? GoTypeReferenceExpressionMixin
        val name = mixin?.referenceName ?: ref.identifier?.text ?: return
        val qualifier = mixin?.qualifierName
        if (qualifier != null) {
            val qRef = ref.referenceExpression
            val targets = GoScopes.resolveName(qRef ?: ref, qualifier)
            val spec = (targets.singleOrNull() as? GoScopes.Target.Declaration)?.element as? GoTypeSpec
            if (spec != null && spec.typeParameters != null && !GoUniverse.isBuiltinDeclaration(spec)) {
                report(qRef ?: ref, "cannot use generic type ${spec.name} without instantiation", "generic-no-instantiation")
                return
            }
            val import = targets.firstOrNull() as? GoScopes.Target.Import ?: return
            usedImports += import.element
            if (import.element.path == "C") return
            if (resolver.resolveTypeReference(ref) != null) return
            val pkg = packages.resolveImport(import.element.path, file) ?: return
            if (packages.scopeOf(pkg).lookup(name).isNotEmpty()) report(ref.identifier ?: ref, "name $name not exported by package ${pkg.name}", "unexported")
            else report(ref.identifier ?: ref, "undefined: $qualifier.$name", "undefined")
            return
        }
        if (name == "_") { report(ref.identifier ?: ref, "cannot use _ as value or type", "blank-value"); return }
        val target = resolver.resolveTypeReference(ref)
        if (target == null) {
            if (!GoUniverse.isBuiltin(name)) report(ref.identifier ?: ref, "undefined: $name", "undefined")
            return
        }
        usedLocals += target
        if (target is GoNamedElement && GoUniverse.isBuiltinDeclaration(target)) return
        if (target !is GoTypeSpec && target !is GoTypeParamDefinition && GoScopes.receiverTypeParamOf(target) == null) {
            val what = when (target) {
                is GoImportSpec -> "package name"
                is GoVarDefinition, is GoParamDefinition, is GoReceiver -> if (GoPsiUtil.isInsideFunctionBody(target) || target !is GoVarDefinition) "local variable" else "package-level variable"
                is io.github.golangsupport.lang.psi.GoConstDefinition -> "constant"
                is GoFunctionDeclaration -> "func"
                else -> null
            }
            report(ref.identifier ?: ref, "$name${what?.let { " ($it)" } ?: ""} is not a type", "not-a-type")
        }
    }

    private fun checkKey(key: GoKey) {
        if (!resolver.isFieldKey(key)) return
        val keyRef = key.expression as? GoReferenceExpression ?: return
        val name = keyRef.referenceName ?: return
        if (resolver.resolveFieldKey(key).isNotEmpty()) return
        val t = resolver.literalTypeOfKey(key) ?: return
        if (!isKnown(t)) return
        report(keyRef, lookupError(t, name, true), "unknown-field")
    }

    /** `x` on the left of `=`, `++`, `range`, `<-` receive (possibly parenthesised) is assigned, not used. */
    private fun isAssignmentTarget(ref: GoReferenceExpression): Boolean {
        var e: PsiElement = ref
        while (e.parent is GoParenthesesExpr) e = e.parent
        val list = e.parent as? GoLeftHandExprList ?: return false
        val stmt = list.parent
        return when (stmt) {
            is GoAssignmentStatement -> stmt.assignOp?.assign != null
            is GoRangeClause, is io.github.golangsupport.lang.psi.GoRecvStatement, is io.github.golangsupport.lang.psi.GoCommCase -> true
            else -> false
        }
    }

    // --- type nodes ---

    /**
     * go/types union.go: a union has at most [MAX_UNION_TERMS] terms, counted syntactically (error at
     * the first term past the limit) and after expanding embedded type sets (error at the union);
     * non-interface terms must be pairwise disjoint (`int|~int` overlaps).
     */
    private fun checkUnion(elem: io.github.golangsupport.lang.psi.GoConstraintElem) {
        val terms = elem.constraintTermList
        // go/types parseTilde: a term must not be a type parameter (a lone `[P T]` constraint is reported by the bound instead).
        val lone = terms.size == 1 && terms[0].tilde == null && elem.parent is io.github.golangsupport.lang.psi.GoTypeParameterDeclaration
        if (!lone) for (t in terms) {
            val node = t.type ?: continue
            if (typer.builder.typeOf(node) !is GoTypeParamType) continue
            report(node, if (t.tilde != null) "type in term ~${exprText(node)} cannot be a type parameter" else "term cannot be a type parameter", "misplaced-type-param")
        }
        if (terms.size > MAX_UNION_TERMS) {
            report(terms[MAX_UNION_TERMS], "cannot handle more than $MAX_UNION_TERMS union terms", "union")
            return
        }
        if (terms.size < 2) return
        val built = terms.map { t -> GoTerm(t.tilde != null, t.type?.let { typer.builder.typeOf(it) } ?: GoUnknownType) }
        val expanded = built.sumOf { t -> termCount(t.type, 0) }
        if (expanded > MAX_UNION_TERMS) {
            report(terms[0], "cannot handle more than $MAX_UNION_TERMS union terms", "union")
            return
        }
        for (i in 1 until built.size) {
            val y = built[i]
            if (!isKnown(y.type) || y.type.underlying() is GoInterfaceType || y.type is GoTypeParamType) continue
            val j = (0 until i).firstOrNull { k ->
                val x = built[k]
                isKnown(x.type) && x.type.underlying() !is GoInterfaceType && x.type !is GoTypeParamType && !disjoint(x, y)
            } ?: continue
            report(terms[i], "overlapping terms ${renderTerm(y)} and ${renderTerm(built[j])}", "union")
        }
    }

    /** The number of terms [t] contributes to a union once embedded interfaces are expanded (`u99|float32` is 100). */
    private fun termCount(t: GoType, depth: Int): Int {
        val iface = t.underlying() as? GoInterfaceType ?: return 1
        val terms = iface.typeTerms ?: return 1
        if (depth > 8) return terms.size
        return terms.sumOf { termCount(it.type, depth + 1) }
    }

    /** go/types term.disjoint. */
    private fun disjoint(x: GoTerm, y: GoTerm): Boolean {
        val ux = if (y.tilde) x.type.underlying() else x.type
        val uy = if (x.tilde) y.type.underlying() else y.type
        return !identical(ux, uy)
    }

    private fun renderTerm(t: GoTerm): String = (if (t.tilde) "~" else "") + render(t.type)

    private fun checkTypeNode(node: PsiType) {
        if (node is io.github.golangsupport.lang.psi.GoMapType) checkMapKey(node)
        if (isVarTypePosition(node) && node !is io.github.golangsupport.lang.psi.GoParType) {
            val t = typer.builder.typeOf(node)
            constraintOnlyCause(t)?.let { report(node, it, "misplaced-constraint"); return }
        }
        val ref = node.typeReferenceExpression ?: return
        val args = node.typeArguments?.typeList
        val target = resolver.resolveTypeReference(ref) as? GoTypeSpec ?: return
        if (args != null && target.typeParameters == null) { reportNotGeneric(ref, target, args); return }
        val params = target.typeParameters?.let { GoScopes.typeParamDefinitions(it) } ?: return
        if (args == null) {
            if (!isInstantiationContext(node)) report(ref, "cannot use generic type ${genericTypeName(target)} without instantiation", "generic-no-instantiation")
            return
        }
        checkTypeArgumentCount(ref, target.name ?: "", "type", params.size, args.size)
        checkConstraints(target, args.map { typer.builder.typeOf(it) }, args)
    }

    /** go/types `instantiatedType`: type arguments on a type without type parameters. */
    private fun reportNotGeneric(x: PsiElement, spec: GoTypeSpec, args: List<PsiElement>) {
        val t = typer.builder.typeOfDeclaration(spec, x)
        // An alias of an uninstantiated generic type is reported at the alias (`type A = List`).
        if (!isKnown(t) || t is GoNamedType && t.isGeneric) return
        val name = exprText(x)
        report(x, "invalid operation: $name${args.joinToString(", ", "[", "]") { exprText(it) }} (${render(t)} is not a generic type)", "type-args")
    }

    /** go/types typexpr: the key of a map type must be comparable (`invalid map key type T (missing comparable constraint)`). */
    private fun checkMapKey(node: io.github.golangsupport.lang.psi.GoMapType) {
        val keyNode = node.typeList.firstOrNull() ?: return
        val key = typer.builder.typeOf(keyNode)
        if (!isKnown(key) || key is GoTypeParamType && !isKnown(key.bound)) return
        if (key !is GoTypeParamType && key.underlying() is GoInterfaceType) return
        if (comparable(key)) return
        report(keyNode, "invalid map key type ${render(key)}${if (key is GoTypeParamType) " (missing comparable constraint)" else ""}", "map-key")
    }

    /** A bare generic type is allowed where the parser attaches type arguments elsewhere (receivers, literals). */
    private fun isInstantiationContext(node: PsiType): Boolean {
        var e: PsiElement? = node.parent
        while (e is PsiType) e = e.parent
        return e is GoCompositeLit || e is GoIndexOrSliceExpr
    }

    /**
     * go/types `nonGeneric`: a generic function named without instantiation where no function type
     * drives its inference (`var f = F`, `_ = F`, `any(F)`, `F == nil`). Reports at the name; true when reported.
     */
    private fun checkNonGeneric(value: GoExpression): Boolean {
        val x = unparen(value) as? GoReferenceExpression ?: return false
        val t = typer.typeOf(x) as? GoSignatureType ?: return false
        if (!t.isGeneric || !isKnown(t)) return false
        // Only function and (Go 1.27 generic) method declarations: a variable of a generic function type is already an error at its declaration.
        val decl = resolver.resolveReferenceExpression(x).singleOrNull()?.element
        if (decl !is GoFunctionDeclaration && decl !is GoMethodDeclaration) return false
        if (decl is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(decl)) return false
        report(x, "cannot use generic function ${exprText(x)} without instantiation", "generic-no-instantiation")
        return true
    }

    /** A generic type named without type arguments in an expression (`T.m`, `new(T)`): its declaration. */
    private fun bareGenericType(e: GoExpression): GoTypeSpec? {
        val x = unparen(e) as? GoReferenceExpression ?: return null
        if (x.qualifier != null) return null
        val spec = resolver.resolveReferenceExpression(x).singleOrNull()?.element as? GoTypeSpec ?: return null
        return spec.takeIf { it.typeParameters != null && !GoUniverse.isBuiltinDeclaration(it) }
    }

    /**
     * go/types `validVarType`: an interface with type terms or `comparable` in its type set is only
     * a constraint. Null for types usable as values (method-only interfaces, non-interfaces, type parameters).
     */
    private fun constraintOnlyCause(t: GoType): String? {
        if (t is GoTypeParamType || !isKnown(t)) return null
        val iface = t.underlying() as? GoInterfaceType ?: return null
        val terms = iface.typeTerms
        if (terms != null) return "cannot use type ${render(t)} outside a type constraint: interface contains type constraints"
        if (iface.isComparableConstraint) return "cannot use type ${render(t)} outside a type constraint: interface is (or embeds) comparable"
        return null
    }

    /** Type nodes in go/types `varType` positions: variable, parameter, result and field types and element types of composite types. */
    private fun isVarTypePosition(node: PsiType): Boolean = when (node.parent) {
        is GoVarSpec, is io.github.golangsupport.lang.psi.GoParameterDeclaration, is io.github.golangsupport.lang.psi.GoResult,
        is io.github.golangsupport.lang.psi.GoFieldDeclaration, is io.github.golangsupport.lang.psi.GoPointerType, is io.github.golangsupport.lang.psi.GoMapType,
        is io.github.golangsupport.lang.psi.GoChannelType -> true
        is io.github.golangsupport.lang.psi.GoArrayOrSliceType -> true
        else -> false
    }

    private fun checkTypeArgumentCount(at: PsiElement, name: String, kind: String, want: Int, have: Int) {
        if (have < want) report(at, if (kind == "type") "not enough type arguments for type $name: have $have, want $want" else "got $have type arguments but want $want", "type-args")
        else if (have > want) report(at, if (kind == "type") "too many type arguments for type $name: have $have, want $want" else "got $have type arguments but want $want", "type-args")
    }

    private fun checkConstraints(decl: GoTypeSpec, args: List<GoType>, at: List<PsiElement>) {
        val params = typer.builder.typeParams(decl.typeParameters)
        checkConstraints(params, args, at)
    }

    /** [from]: the first type argument to check (explicit ones are checked where they are written). */
    private fun checkConstraints(params: List<GoTypeParamType>, args: List<GoType>, at: List<PsiElement>, partial: GoSubstitution = GoSubstitution.EMPTY, from: Int = 0) {
        if (params.size != args.size) return
        val subst = partial + GoSubstitution.of(params, args)
        for ((i, p) in params.withIndex()) {
            if (i < from) continue
            val arg = args[i]
            if (!isKnown(arg)) continue
            val bound = p.bound.substitute(subst)
            if (!isKnown(bound)) continue
            val failure = GoTypePredicates.satisfactionFailure(arg, bound, ::render) ?: continue
            at.getOrNull(i)?.let { report(it, failure, "constraint") }
        }
    }

    // --- declarations ---

    private fun checkVarSpec(spec: GoVarSpec) {
        val defs = spec.varDefinitionList
        val values = spec.expressionList
        val declared = spec.type?.let { typer.builder.typeOf(it) }
        if (values.isEmpty()) return
        checkArity(defs.size, values, spec, "variable", spec.assign, declaration = true, names = defs)
        if (declared != null) {
            if (!isKnown(declared)) return
            // A sole tuple-valued initializer is reported by checkArity.
            if (values.size == defs.size) values.forEach { if (values.size != 1 || typer.typeOf(it) !is GoTupleType) checkAssignable(it, declared, "variable declaration") }
            else if (values.size == 1) checkTupleAssignable(values[0], List(defs.size) { declared }, "variable declaration")
        } else {
            for (v in values) {
                if (checkNonGeneric(v)) continue
                if (isNilLiteral(v)) report(v, "use of untyped nil in variable declaration", "untyped-nil")
                checkValueUsable(v, sole = values.size == 1)
            }
        }
    }

    private fun checkConstSpec(spec: GoConstSpec) {
        val declared = spec.type?.let { typer.builder.typeOf(it) }
        if (spec.expressionList.isNotEmpty()) checkArity(spec.constDefinitionList.size, spec.expressionList, spec, "variable", spec.assign, declaration = true, names = spec.constDefinitionList)
        else {
            // Implicit repetition of the previous spec's expressions (spec "Constant declarations"); a spec with an explicit type repeats nothing.
            // The typer's cached per-declaration layout: walking siblings here was quadratic on huge groups (opGen.go, 8421 specs).
            val source = if (spec.type != null) null else typer.repeatedConstSpec(spec)?.takeIf { it.expressionList.isNotEmpty() }
            if (source == null) {
                spec.constDefinitionList.forEach { report(it, "missing init expr for ${it.name}", "assignment-mismatch") }
                return
            }
            val values = source.expressionList
            val defs = spec.constDefinitionList
            if (values.size < defs.size) report(defs[values.size], "missing init expr for ${defs[values.size].name}", "assignment-mismatch")
            else if (values.size > defs.size) report(spec, "extra init expr at ${values[defs.size].text}", "assignment-mismatch")
            val t = (declared ?: source.type?.let { typer.builder.typeOf(it) })?.takeIf { isKnown(it) }
            val tb = t?.underlying() as? GoBasicType
            if (tb != null) for (def in defs) {
                val c = typer.constantValueOf(def) ?: continue
                val f = representabilityFailure(c, tb.kind) ?: continue
                report(def, if (f == "overflows") "constant ${c.render()} overflows ${render(t)}" else "cannot use ${c.render()} as ${render(t)} value in constant declaration ($f)", "representability")
            }
            return
        }
        // go/types `constDecl` stops at an invalid constant type (checkConstType) before evaluating the values.
        if (declared != null && isKnown(declared) && !isConstType(declared)) return
        for (v in spec.expressionList) {
            val c = typer.constantOf(v)
            if (c == null) {
                if (typer.isTypeExpression(v)) { report(v, "${exprText(v)} (type) is not an expression", "not-expression"); continue }
                val t = typer.typeOf(v)
                if (isKnown(t) && t !is GoTupleType && isDefinitelyNotConstant(v)) report(v, "${describe(v)} is not constant", "not-constant")
                continue
            }
            if (declared != null && isKnown(declared)) checkAssignable(v, declared, "constant declaration")
        }
    }

    /** Variables, calls of ordinary functions, composite literals and receives are never constant; everything else may be one we cannot fold. */
    private fun isDefinitelyNotConstant(e: GoExpression): Boolean {
        val x = unparen(e)
        return when (x) {
            is GoReferenceExpression -> if (x.qualifier == null) resolver.resolveReferenceExpression(x).firstOrNull()?.element.let { it is GoVarDefinition || it is GoParamDefinition || it is GoReceiver } else isVariable(x)
            is GoCallExpr -> {
                val callee = x.expression ?: return false
                val c = unparen(callee)
                // A conversion to a type parameter or a non-basic type is never constant.
                if (typer.isTypeExpression(callee) && typer.typeOf(callee).let { it is GoTypeParamType || isKnown(it) && it.underlying() !is GoBasicType }) return true
                if (typer.isTypeExpression(callee)) return (GoPsiUtil.children(x.argumentList, GoExpression::class.java).firstOrNull()?.let(::isDefinitelyNotConstant) ?: false)
                if (c is GoReferenceExpression && c.qualifier == null && isBuiltinCallee(c)) {
                    val args = x.arguments.filterIsInstance<GoExpression>()
                    return when (c.referenceName) {
                        // len/cap are constant for string constants and for arrays without calls or receives.
                        "len", "cap" -> args.singleOrNull()?.let { a ->
                            val at = typer.typeOf(a)
                            if (typer.constantOf(a) is GoConstant.Str) false
                            else if ((at.underlying() as? GoArrayType) != null || (at.underlying() as? GoPointerType)?.elem?.underlying() is GoArrayType) hasCallOrReceive(a)
                            else isKnown(at)
                        } ?: false
                        "real", "imag", "complex", "min", "max" -> args.any { typer.constantOf(it) == null && isKnown(typer.typeOf(it)) }
                        else -> true
                    }
                }
                if (isUnsafeCall(c)) {
                    val args = x.arguments.filterIsInstance<GoExpression>()
                    val name = (c as GoReferenceExpression).referenceName
                    return when (name) {
                        "Sizeof", "Alignof" -> args.any { hasVarSize(typer.typeOf(it)) }
                        // The offset depends on the layout of the whole struct holding the field.
                        "Offsetof" -> args.any { a -> ((unparen(a) as? GoReferenceExpression)?.qualifier as? GoExpression)?.let { hasVarSize(typer.typeOf(it)) } ?: false }
                        else -> true
                    }
                }
                true
            }
            is GoCompositeLit, is GoFunctionLit, is GoIndexOrSliceExpr, is GoTypeAssertionExpr -> true
            is GoUnaryExpr -> x.operator === GoTypes.ARROW || x.operator === GoTypes.AND || x.expression?.let(::isDefinitelyNotConstant) == true
            is GoBinaryExpr -> x.left?.let(::isDefinitelyNotConstant) == true || x.right?.let(::isDefinitelyNotConstant) == true
            else -> false
        }
    }

    /** Calls of non-builtin functions and channel receives anywhere inside [e] (go/types: `len(a)` is not constant then). */
    private fun hasCallOrReceive(e: GoExpression): Boolean {
        fun isCall(c: GoCallExpr): Boolean {
            val callee = c.expression ?: return false
            if (typer.isTypeExpression(callee)) return false
            val r = unparen(callee)
            // A builtin call that folds to a constant (`real(2i)`, `len("s")`) is not a function call here.
            if (r is GoReferenceExpression && r.qualifier == null && isBuiltinCallee(r)) return typer.constantOf(c) == null
            return true
        }
        if (e is GoCallExpr && isCall(e)) return true
        if (e is GoUnaryExpr && e.operator === GoTypes.ARROW) return true
        return PsiTreeUtil.findChildrenOfType(e, GoCallExpr::class.java).any(::isCall) ||
            PsiTreeUtil.findChildrenOfType(e, GoUnaryExpr::class.java).any { it.operator === GoTypes.ARROW }
    }

    /**
     * go/types `hasVarSize`: the size of [t] depends on a type parameter (a type parameter held
     * directly, in an array element or a struct field). Pointers, slices, maps, channels, functions
     * and interfaces have a fixed size.
     */
    private fun hasVarSize(t: GoType, depth: Int = 0): Boolean = depth < 8 && when (t) {
        is GoTypeParamType -> true
        is GoArrayType -> hasVarSize(t.elem, depth + 1)
        is GoStructType -> t.fields.any { hasVarSize(it.type, depth + 1) }
        is GoNamedType -> t.underlying().let { it !== t && hasVarSize(it, depth + 1) }
        else -> false
    }

    private fun checkShortVarDecl(decl: GoShortVarDeclaration) {
        val defs = decl.varDefinitionList
        val values = decl.expressionList
        if (values.isEmpty()) return
        checkArity(defs.size, values, decl, "variable", decl.define)
        for (v in values) {
            if (checkNonGeneric(v)) continue
            if (isNilLiteral(v)) report(v, "use of untyped nil in assignment", "untyped-nil")
            checkValueUsable(v, sole = values.size == 1)
        }
        var erroneous = false
        val seen = HashSet<String>()
        for (d in defs) {
            val n = d.name ?: continue
            if (n != "_" && !seen.add(n)) { report(d, "$n repeated on left side of :=", "redeclared"); erroneous = true }
            // A name already declared in this scope must be a variable to be assigned to.
            if (n != "_" && decl.parent !is GoRangeClause && isRedeclaredInSameScope(d)) {
                val existing = GoScopes.resolveName(decl, n).firstOrNull()?.element
                if (existing != null && existing !is GoVarDefinition && existing !is GoParamDefinition && existing !is GoReceiver) { report(d, "cannot assign to $n", "unassignable"); erroneous = true }
            }
        }
        if (decl.parent !is GoRangeClause && defs.isNotEmpty() && defs.all { it.name == "_" || isRedeclaredInSameScope(it) }) {
            report(decl.define ?: decl, "no new variables on left side of :=", "no-new-variables")
            erroneous = true
        }
        if (erroneous) usedLocals += defs // go/types marks the variables used after a declaration error
    }

    private fun isRedeclaredInSameScope(def: GoVarDefinition): Boolean {
        val name = def.name ?: return false
        val stmt = (def.parent as? GoLabeledStatement)?.statement?.let { def.parent } ?: def.parent ?: return false
        val holder = if (stmt.parent is GoLabeledStatement) stmt.parent else stmt
        val block = holder.parent as? GoBlock ?: return false
        if (block.statementList.any { s -> s !== holder && s.textRange.startOffset < holder.textRange.startOffset && GoPsiUtil.declarationsOf(s).any { it.name == name } }) return true
        // Parameters, results and the receiver share the function's outermost block.
        val owner = block.parent
        val sig = when (owner) { is GoFunctionDeclaration -> owner.signature; is GoMethodDeclaration -> owner.signature; is GoFunctionLit -> owner.signature; else -> null } ?: return false
        if (owner is GoMethodDeclaration && owner.receiver?.name == name) return true
        return (sig.parameters?.parameterDeclarationList.orEmpty() + sig.result?.parameters?.parameterDeclarationList.orEmpty()).any { d -> d.paramDefinitionList.any { it.name == name } }
    }

    private fun checkAssignment(stmt: GoAssignmentStatement) {
        val lhs = stmt.leftHandExprList?.expressionList ?: return
        val rhs = stmt.expressionList
        val op = stmt.assignOp ?: return
        if (op.assign != null) {
            if (rhs.isEmpty()) return
            checkArity(lhs.size, rhs, stmt, "variable", op, init = false)
            if (lhs.size == rhs.size) {
                for (i in lhs.indices) {
                    val target = lhs[i]
                    val value = rhs[i]
                    checkValueUsable(value, sole = rhs.size == 1)
                    if (!checkAssignTarget(target)) continue
                    if (isBlank(target)) {
                        if (checkNonGeneric(value)) continue
                        if (isNilLiteral(value)) report(value, "use of untyped nil in assignment", "untyped-nil")
                        continue
                    }
                    val tt = typer.typeOf(target)
                    if (tt is GoTupleType) { if (tt.types.isEmpty()) report(target, "${exprText(target)} (no value) used as value", "no-value"); continue }
                    if (!isKnown(tt) || typer.constantOf(target) != null) continue
                    checkAssignable(value, tt, "assignment")
                }
            } else if (rhs.size == 1) {
                lhs.forEach { checkAssignTarget(it) }
                val targets = lhs.map { if (isBlank(it)) null else typer.typeOf(it) }
                checkTupleAssignable(rhs[0], targets, "assignment")
            }
            return
        }
        // op-assign: x op= y behaves like x = x op y
        val target = lhs.singleOrNull() ?: return
        val value = rhs.singleOrNull() ?: return
        if (!checkAssignTarget(target)) return
        val opText = op.text.removeSuffix("=")
        checkBinaryOperands(target, value, opText, stmt)
    }

    private fun isBlank(e: GoExpression): Boolean = e is GoReferenceExpression && e.qualifier == null && e.referenceName == "_"

    /** go/types `assignError` / `initVars`: [declaration] selects the "extra/missing init expr" wording of var declarations. */
    private fun checkArity(count: Int, values: List<GoExpression>, at: PsiElement, what: String, anchor: PsiElement?, declaration: Boolean = false, names: List<PsiElement> = emptyList(), init: Boolean = true) {
        if (values.size == count && !(count == 1 && typer.typeOf(values[0]).let { it is GoTupleType && it.types.size != 1 })) return
        if (values.size == 1) {
            val t = typer.typeOf(values[0])
            if (t is GoTupleType && count == 1) {
                // A single target takes a single value: go/types initVars (var, :=) reports the operand, assignVars (=) the mismatch.
                if (t.types.isEmpty()) report(values[0], "${exprText(values[0])} (no value) used as value", "no-value")
                else if (init) report(values[0], "multiple-value ${exprText(values[0])} (value of type ${render(t)}) in single-value context", "multiple-value")
                else {
                    val callee = (unparen(values[0]) as? GoCallExpr)?.expression?.let(::exprText) ?: exprText(values[0])
                    report(values[0], "assignment mismatch: 1 variable but $callee returns ${t.types.size} values", "assignment-mismatch")
                }
                return
            }
            if (t is GoTupleType) {
                if (t.types.size != count) {
                    val callee = (unparen(values[0]) as? GoCallExpr)?.expression?.let(::exprText) ?: exprText(values[0])
                    report(values[0], "assignment mismatch: $count ${plural(count, what)} but $callee returns ${t.types.size} ${plural(t.types.size, "value")}", "assignment-mismatch")
                }
                return
            }
            if (t is GoUnknownType) return
            if (count == 2 && isCommaOk(values[0])) return
            report(values[0], "assignment mismatch: $count ${plural(count, what)} but 1 value", "assignment-mismatch")
            return
        }
        if (declaration) {
            if (values.size > count) report(values[count], "extra init expr ${exprText(values[count])}", "assignment-mismatch")
            else report(names.getOrNull(values.size) ?: anchor ?: at, "missing init expr for ${names.getOrNull(values.size)?.text ?: "_"}", "assignment-mismatch")
            return
        }
        report(values[0], "assignment mismatch: $count ${plural(count, what)} but ${values.size} ${plural(values.size, "value")}", "assignment-mismatch")
    }

    private fun isCommaOk(e: GoExpression): Boolean {
        val x = unparen(e)
        if (x is GoTypeAssertionExpr || x is GoUnaryExpr && x.operator === GoTypes.ARROW) return true
        if (x is GoIndexOrSliceExpr && !x.isSlice) {
            val t = typer.typeOf(x.expression ?: return false)
            val u = (if (t is GoTypeParamType) t.coreType else null) ?: t.underlying()
            return u is GoMapType || t is GoTypeParamType && t.coreType == null
        }
        return false
    }

    private fun plural(n: Int, word: String): String = if (n == 1) word else word + "s"

    private fun checkTupleAssignable(value: GoExpression, targets: List<GoType?>, context: String) {
        val t = typer.typeOf(value) as? GoTupleType ?: return
        if (t.types.size != targets.size) return
        for (i in targets.indices) {
            val target = targets[i] ?: continue
            val vt = t.types[i]
            if (!isKnown(target) || !isKnown(vt)) continue
            if (!assignable(vt, target)) {
                val callee = (unparen(value) as? GoCallExpr)?.expression?.text ?: value.text
                report(value, "cannot use ${exprText(value)} (value of type ${render(vt)}) as ${render(target)} value in $context", "assignability")
                if (callee.isEmpty()) return
                return
            }
        }
    }

    private fun checkReturn(stmt: GoReturnStatement) {
        val owner = GoPsiUtil.functionOwner(stmt) ?: return
        val sig = when (owner) {
            is GoFunctionDeclaration -> typer.builder.functionType(owner)
            is GoMethodDeclaration -> typer.builder.methodOf(owner, null)?.signature
            is GoFunctionLit -> typer.builder.signatureOf(owner.signature, null, null)
            else -> null
        } ?: return
        val values = stmt.expressionList
        val results = sig.results
        if (values.isEmpty()) {
            if (results.isNotEmpty() && results.any { it.name == null }) report(stmt, "not enough return values\n\thave ()\n\twant ${renderParams(results)}", "return-arity")
            return
        }
        if (results.isEmpty()) { report(values[0], "too many return values\n\thave ${renderHave(values)}\n\twant ()", "return-arity"); return }
        if (values.size == 1 && results.size > 1) {
            val t = typer.typeOf(values[0])
            if (t is GoTupleType) {
                if (t.types.size != results.size) report(values[0], (if (t.types.size < results.size) "not enough return values" else "too many return values") + "\n\thave ${renderHave(values)}\n\twant ${renderParams(results)}", "return-arity")
                else checkTupleAssignable(values[0], results.map { it.type }, "return statement")
                return
            }
            if (t is GoUnknownType) return
        }
        if (values.size != results.size) {
            report(values[0], (if (values.size < results.size) "not enough return values" else "too many return values") + "\n\thave ${renderHave(values)}\n\twant ${renderParams(results)}", "return-arity")
            return
        }
        for (i in values.indices) {
            val rt = results[i].type
            if (!isKnown(rt)) continue
            checkAssignable(values[i], rt, "return statement")
        }
    }

    private fun renderParams(params: List<GoParam>): String = params.joinToString(", ", "(", ")") { render(it.type) }

    private fun renderHave(values: List<GoExpression>): String = values.joinToString(", ", "(", ")") { haveType(typer.typeOf(it)) }

    private fun haveType(t: GoType): String = when {
        t is GoBasicType && t.kind == GoBasicKind.UNTYPED_NIL -> "nil"
        t is GoBasicType && t.isUntyped -> when (t.kind) { GoBasicKind.UNTYPED_BOOL -> "bool"; GoBasicKind.UNTYPED_STRING -> "string"; else -> "number" }
        t is GoUnknownType -> "unknown type"
        else -> render(t)
    }

    // --- calls ---

    private fun checkCall(call: GoCallExpr) {
        val callee = call.expression ?: return
        val args = call.arguments
        val calleeRef = unparen(callee)
        if (calleeRef is GoReferenceExpression && calleeRef.qualifier == null) {
            val name = calleeRef.referenceName ?: ""
            val results = resolver.resolveReferenceExpression(calleeRef)
            if ((results.isEmpty() && name in GoUniverse.FUNCTIONS) || results.any { it.element is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(it.element as GoFunctionDeclaration) }) {
                checkBuiltinCall(name, call)
                return
            }
        }
        if (typer.isTypeExpression(callee)) {
            checkConversion(call, typer.typeOf(callee), args)
            return
        }
        // `T.m()` with generic T: reported at the selector (the operand is invalid).
        if ((calleeRef as? GoReferenceExpression)?.qualifier?.let { it is GoExpression && bareGenericType(it) != null } == true) return
        if (isUnsafeCall(calleeRef)) { checkUnsafeCall((calleeRef as GoReferenceExpression).referenceName ?: "", call); return }
        val calleeType = typer.typeOf(callee)
        if (calleeType is GoUnknownType) return
        val sig = typer.calleeSignature(call)
        if (sig == null) {
            // A conversion to a type parameter, also one declared by a method receiver (`func (d *T[EI]) f() { EI(0) }`).
            if (calleeType is GoTypeParamType && (typer.isTypeExpression(callee) ||
                    (unparen(callee) as? GoReferenceExpression)?.let { r -> r.qualifier == null && r.referenceName == calleeType.name } == true)) return
            if (calleeType is GoTypeParamType) {
                val core = calleeType.coreType
                val nonFunction = calleeType.terms?.firstOrNull { it.type.underlying() !is GoSignatureType }?.type
                if (nonFunction != null) report(call, "invalid operation: cannot call ${describe(callee)}: ${render(nonFunction)} is not a function", "non-function")
                else if (core == null) report(call, "invalid operation: cannot call ${describe(callee)}: no specific type", "non-function")
            } else if (isKnown(calleeType) && calleeType !is GoTupleType) report(call, "invalid operation: cannot call ${describe(callee)}: ${render(calleeType.underlying())} is not a function", "non-function")
            return
        }
        val name = calleeText(callee)
        val spread = call.argumentList?.hasEllipsis == true
        for (a in args) {
            if (a !is GoExpression) continue
            val t = typer.typeOf(a)
            if (t is GoTupleType) {
                if (t.types.isEmpty()) { report(a, "${exprText(a)} (no value) used as value", "no-value"); return }
                if (args.size > 1) { report(a, "multiple-value ${exprText(a)} (value of type ${render(t)}) in single-value context", "multiple-value"); return }
            }
        }
        if (spread && !sig.variadic) {
            report(call.argumentList ?: call, "have (...) but function is not variadic: cannot use ... in call to non-variadic $name", "spread")
            return
        }
        // Expand a single multi-value argument.
        val argTypes: List<Pair<PsiElement?, GoType>> = if (args.size == 1 && args[0] is GoExpression && typer.typeOf(args[0] as GoExpression) is GoTupleType) {
            (typer.typeOf(args[0] as GoExpression) as GoTupleType).types.map { null to it }
        } else args.map { it to (if (it is GoExpression) typer.typeOf(it) else typer.builder.typeOf(it as PsiType)) }
        val required = if (sig.variadic) sig.params.size - 1 else sig.params.size
        val haveN = argTypes.size
        if (haveN < required || (!sig.variadic && haveN > sig.params.size) || (sig.variadic && spread && haveN != sig.params.size)) {
            if (argTypes.any { it.second is GoUnknownType && it.first == null }) return
            val which = if (haveN < required) "not enough arguments" else "too many arguments"
            val have = argTypes.joinToString(", ", "(", ")") { haveType(it.second) } + (if (spread) "..." else "")
            val want = sig.params.joinToString(", ", "(", ")") { p -> if (sig.variadic && p === sig.params.last()) "..." + render((p.type as? GoSliceType)?.elem ?: p.type) else render(p.type) }
            report(if (haveN < required) (call.argumentList?.rparen ?: call) else (argTypes.getOrNull(required)?.first ?: call), "$which in call to $name\n\thave $have\n\twant $want", "call-arity")
            return
        }
        if (sig.isGeneric) {
            val unresolved = sig.typeParams.firstOrNull()
            if (unresolved != null && (argTypes.all { isKnown(it.second) } || isUninferable(sig, unresolved, argTypes))) {
                report(call.argumentList?.rparen ?: call, "in call to $name, cannot infer ${unresolved.name}", "cannot-infer")
                return
            }
        }
        // Constraint satisfaction of the inferred / explicit type arguments.
        checkCallConstraints(call, callee)
        val generic = genericSignatureOf(callee)
        for ((i, pair) in argTypes.withIndex()) {
            val (arg, at) = pair
            val pt = GoInference.paramTypeAt(sig, i, argTypes.size, spread) ?: break
            if (!isKnown(pt) || GoInference.containsParams(pt, sig.typeParams.toSet())) continue
            if (generic != null && unparen(callee) !is GoIndexOrSliceExpr && arg is GoExpression && isKnown(at) && !isUntyped(at) && !(at is GoSignatureType && at.isGeneric) && !assignable(at, pt)) {
                val gp = GoInference.paramTypeAt(generic, i, argTypes.size, spread)
                if (gp is GoTypeParamType) { report(arg, "type ${render(at)} of ${exprText(arg)} does not match inferred type ${render(pt)} for ${gp.name}", "inference"); continue }
                if (gp != null && GoInference.containsParams(gp, generic.typeParams.toSet())) { report(arg, "type ${render(at)} of ${exprText(arg)} does not match ${render(gp)}", "inference"); continue }
            }
            if (arg is GoExpression) checkAssignable(arg, pt, "argument to $name")
            else if (arg == null && isKnown(at) && !assignable(at, pt)) {
                report(args[0], "cannot use ${args[0].text} (value of type ${render(at)}) as ${render(pt)} value in argument to $name", "assignability")
                return
            }
        }
    }

    /**
     * Whether [p] (the first parameter inference left unbound) cannot be inferred whatever the
     * unknown argument types are: it occurs in no parameter type, its constraint gives no core type
     * or single term, and no other unbound parameter's constraint mentions it (constraint type
     * inference could bind it from there). Generic function arguments may carry it: then not.
     */
    private fun isUninferable(sig: GoSignatureType, p: GoTypeParamType, args: List<Pair<PsiElement?, GoType>>): Boolean {
        val only = setOf(p)
        if (sig.params.any { GoInference.containsParams(it.type, only) }) return false
        if (args.any { (_, t) -> t is GoSignatureType && t.isGeneric }) return false
        // go/types skips inference when an argument is invalid: an unknown argument must come from a
        // missing dependency, not from a name that does not resolve.
        for ((arg, t) in args) {
            if (isKnown(t)) continue
            if (arg == null) return false
            if (PsiTreeUtil.findChildrenOfType(arg, GoReferenceExpression::class.java).plus(listOfNotNull(arg as? GoReferenceExpression)).any { r ->
                    r.referenceName != "_" && resolver.resolveReferenceExpression(r).isEmpty() && !(r.qualifier == null && GoUniverse.isBuiltin(r.referenceName ?: ""))
                }) return false
        }
        if (!isKnown(p.bound) || p.coreType != null || p.singleExactTerm != null) return false
        return sig.typeParams.none { it != p && GoInference.containsParams(it.bound, only) }
    }

    /** `f[int](x)` / `F(x)` where `F` is generic: every inferred type argument must satisfy its constraint. */
    private fun checkCallConstraints(call: GoCallExpr, callee: GoExpression) {
        val generic = genericSignatureOf(callee) ?: return
        val instantiated = typer.calleeSignature(call) ?: return
        if (generic.typeParams.isEmpty()) return
        val explicitArgs = (unparen(callee) as? GoIndexOrSliceExpr)?.indices ?: emptyList()
        val u = io.github.golangsupport.semantic.infer.GoUnifier(generic.typeParams.toSet())
        generic.params.indices.forEach { i -> instantiated.params.getOrNull(i)?.let { u.unify(generic.params[i].type, it.type, exact = true) } }
        generic.results.indices.forEach { i -> instantiated.results.getOrNull(i)?.let { u.unify(generic.results[i].type, it.type, exact = true) } }
        // Parameters that occur only in constraints (`[T any, C chan T | <-chan T](ch C)`): go/types core type unification.
        val tset = generic.typeParams.toSet()
        repeat(2) {
            for (tp in generic.typeParams) {
                val b = u.bindings[tp] ?: continue
                val core = tp.coreType?.substitute(generic.partialSubst) ?: continue
                if (!GoInference.containsParams(core, tset)) continue
                val trial = io.github.golangsupport.semantic.infer.GoUnifier(tset)
                trial.bindings.putAll(u.bindings)
                if (trial.unify(core, b)) trial.bindings.forEach { (k, v) -> if (k !in u.bindings && isKnown(v) && !GoInference.containsParams(v, tset)) u.bindings[k] = v }
            }
        }
        val args = generic.typeParams.map { u.bindings[it] ?: GoUnknownType }
        val at = generic.typeParams.indices.map { explicitArgs.getOrNull(it) ?: call.argumentList ?: call }
        // Explicit type arguments (`f[int]`) are checked by the instantiation itself (checkIndex).
        checkConstraints(generic.typeParams, args, at, generic.partialSubst, from = explicitArgs.size)
    }

    private fun genericSignatureOf(callee: GoExpression): GoSignatureType? {
        val x = unparen(callee)
        val base = if (x is GoIndexOrSliceExpr && !x.isSlice) x.expression ?: return null else x
        val t = typer.typeOf(base)
        val sig = t.underlying() as? GoSignatureType ?: return null
        return sig.takeIf { it.isGeneric }
    }

    private fun calleeText(callee: GoExpression): String = exprText(unparen(callee))

    private fun isBuiltinCallee(c: GoReferenceExpression): Boolean {
        val results = resolver.resolveReferenceExpression(c)
        return results.isEmpty() && (c.referenceName ?: "") in GoUniverse.FUNCTIONS || results.any { it.element is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(it.element as GoFunctionDeclaration) }
    }

    /** `unsafe.Sizeof(x)` and friends take any operand; their results are constants. */
    private fun isUnsafeCall(callee: GoExpression): Boolean {
        val q = (callee as? GoReferenceExpression)?.qualifier as? GoReferenceExpression ?: return false
        if (q.qualifier != null) return false
        val import = resolver.resolveReferenceExpression(q).firstOrNull { it is GoResolver.Result.Import } as? GoResolver.Result.Import ?: return false
        return import.element.path == "unsafe"
    }

    private fun checkBuiltinCall(name: String, call: GoCallExpr) {
        val args = call.arguments
        fun exprArg(i: Int): GoExpression? = args.getOrNull(i) as? GoExpression
        if (call.argumentList?.hasEllipsis == true && name != "append") { report(call, "invalid operation: invalid use of ... with built-in $name", "builtin-arity"); return }
        var count = args.size
        val singleValued = name in setOf("len", "cap", "make", "panic", "real", "imag", "close", "clear", "min", "max")
        for (a in args) {
            if (a !is GoExpression) continue
            if (name == "make" && a === args[0] && !typer.isTypeExpression(a)) { report(a, "${describe(a)} is not a type", "builtin-arg"); return }
            if ((name == "print" || name == "println") && isNilLiteral(a)) { report(a, "use of untyped nil in argument to built-in $name", "untyped-nil"); continue }
            val t = typer.typeOf(a)
            if (t is GoTupleType) {
                if (t.types.isEmpty()) { report(a, "${exprText(a)} (no value) used as value${if (name == "new") " or type" else ""}", "no-value"); return }
                if (args.size == 1 && !singleValued && name != "new") count = t.types.size
                else if (args.size == 1 && singleValued) { report(call, "too many arguments for ${exprText(call)} (expected 1, found ${t.types.size})", "builtin-arity"); return }
                else { report(a, "multiple-value ${exprText(a)} (value of type ${render(t)}) in single-value context", "multiple-value"); return }
            }
        }
        val range: IntRange? = when (name) {
            "len", "cap", "new", "panic", "real", "imag", "close", "clear" -> 1..1
            "copy", "delete", "complex" -> 2..2
            "recover" -> 0..0
            "append", "min", "max", "make" -> 1..Int.MAX_VALUE
            else -> null
        }
        if (range != null && count !in range) {
            val expected = if (range.first == range.last) "${range.first}" else "${range.first}"
            val which = if (count < range.first) "not enough arguments for ${exprText(call)} (expected $expected, found $count)" else "too many arguments for ${exprText(call)} (expected ${if (range.last == Int.MAX_VALUE) range.first else range.last}, found $count)"
            report(call, which, "builtin-arity")
            return
        }
        if (name == "append" && call.argumentList?.hasEllipsis == true && count != 2) {
            if (count < 2) report(call, "not enough arguments for ${exprText(call)} (expected 2, found $count)", "builtin-arity")
            else report(args[2], "too many arguments for ${exprText(call)} (expected 2, found $count)", "builtin-arity")
            return
        }
        if (name == "append" && count != args.size) { checkAppendTupleOperand(call); return }
        if (count != args.size) return // a spread tuple argument: types are checked by the callee rules below only for plain arguments
        when (name) {
            "len", "cap" -> {
                val a = exprArg(0) ?: return
                val t = typer.typeOf(a)
                if (!isKnown(t)) return
                fun supports(u: GoType): Boolean = when (u) {
                    is GoBasicType -> u.kind.isString && name == "len"
                    is GoArrayType, is GoSliceType, is GoChanType -> true
                    is GoMapType -> name == "len"
                    is GoPointerType -> u.elem.underlying() is GoArrayType
                    else -> false
                }
                val ok = if (t is GoTypeParamType) t.terms?.let { ts -> ts.isNotEmpty() && ts.all { supports(it.type.underlying()) } } ?: false else supports(t.underlying())
                if (!ok) report(a, "invalid argument: ${describe(a)} for built-in $name", "builtin-arg")
            }
            "append" -> {
                val a = exprArg(0) ?: return
                val t = typer.typeOf(a)
                if (!isKnown(t)) return
                if (t is GoTupleType) return
                val elem = when (val r = sliceElem(a, t)) { is SliceElem.Elem -> r.type; is SliceElem.Error -> { report(a, "invalid append: ${r.message}", "builtin-arg"); return }; null -> return }
                if (call.argumentList?.hasEllipsis == true) return
                for (i in 1 until args.size) exprArg(i)?.let { if (isKnown(elem)) checkAssignable(it, elem, "argument to append") }
            }
            "delete" -> {
                val m = exprArg(0) ?: return
                val mt = typer.typeOf(m)
                if (!isKnown(mt)) return
                val key = deleteKeyType(m, mt) ?: return
                exprArg(1)?.let { if (isKnown(key)) checkAssignable(it, key, "argument to delete") }
            }
            "close" -> {
                val a = exprArg(0) ?: return
                val t = typer.typeOf(a)
                if (!isKnown(t)) return
                checkClose(a, t)
            }
            "new" -> exprArg(0)?.let { a -> if (typer.isTypeExpression(a)) {
                bareGenericType(a)?.let { report(a, "cannot use generic type ${it.name} without instantiation", "generic-no-instantiation") }
                    ?: constraintOnlyCause(typer.typeOf(a))?.let { report(a, it, "misplaced-constraint") }
            } else if (checkNewOperand(a) && typer.constantOf(a) != null && isUntyped(typer.typeOf(a))) {
                val dt = GoTypePredicates.defaultType(typer.typeOf(a)) as? GoBasicType
                val f = dt?.let { representabilityFailure(typer.constantOf(a)!!, it.kind) }
                if (f != null) report(a, "cannot use ${describe(a)} as ${render(dt)} value in argument to new ($f)", "representability")
            } }
            "make" -> {
                val t = when (val a = args[0]) { is PsiType -> typer.builder.typeOf(a); is GoExpression -> if (typer.isTypeExpression(a)) typer.typeOf(a) else { report(a, "${exprText(a)} is not a type", "builtin-arg"); return }; else -> return }
                if (!isKnown(t)) return
                val makeable = { x: GoType -> x.underlying().let { it is GoSliceType || it is GoMapType || it is GoChanType } }
                if (t is GoTypeParamType && specificTypes(t)?.all(makeable) == false) { report(args[0], "invalid argument: cannot make ${exprText(args[0])}: type must be slice, map, or channel", "builtin-arg"); return }
                val u = if (t is GoTypeParamType) coreTypeOrCause(t).let { (core, cause) -> core ?: run { report(args[0], "invalid argument: cannot make ${exprText(args[0])}: $cause", "builtin-arg"); return } } else t.underlying()
                val max = when (u) { is GoSliceType -> 3; is GoMapType, is GoChanType -> 2; else -> { report(args[0], "invalid argument: cannot make ${exprText(args[0])}: type must be slice, map, or channel", "builtin-arg"); return } }
                if (args.size == 3) {
                    val len = exprArg(1)?.let { typer.constantOf(it) }?.toBigInteger()
                    val cap = exprArg(2)?.let { typer.constantOf(it) }?.toBigInteger()
                    if (len != null && cap != null && len.signum() >= 0 && cap.signum() >= 0 && len.bitLength() < 63 && cap.bitLength() < 63 && len > cap) { report(args[1], "invalid argument: length and capacity swapped", "builtin-arg"); return }
                }
                if (u is GoSliceType && args.size < 2) report(call, "invalid operation: ${exprText(call)} expects 2 or 3 arguments; found ${args.size}", "builtin-arity")
                else if (args.size > max) report(call, "invalid operation: ${exprText(call)} expects ${if (max == 3) "2 or 3" else "1 or 2"} arguments; found ${args.size}", "builtin-arity")
                for (i in 1 until args.size) exprArg(i)?.let { a -> checkIntegerArgument(a, "int", nonNegative = true) }
            }
            "clear" -> {
                val a = exprArg(0) ?: return
                val t = typer.typeOf(a)
                if (!isKnown(t)) return
                val us = typeSetUnderlying(t) ?: return
                if (us.any { it !is GoMapType && it !is GoSliceType }) report(a, "invalid argument: cannot clear ${describe(a)}: argument must be (or constrained by) map or slice", "builtin-arg")
            }
            "min", "max" -> {
                var previous: GoType? = null
                var previousArg: GoExpression? = null
                for (i in args.indices) exprArg(i)?.let { a ->
                    val t = typer.typeOf(a)
                    if (!isKnown(t)) return
                    if (!((t.underlying() as? GoBasicType)?.kind?.isOrdered ?: false) && !(t is GoTypeParamType && (t.terms?.all { (it.type.underlying() as? GoBasicType)?.kind?.isOrdered == true } ?: false))) { report(a, "invalid argument: ${describe(a)} cannot be ordered", "builtin-arg"); return }
                    // All arguments must have the same type after untyped conversion.
                    val p = previous
                    if (p != null && !isUntyped(t) && !isUntyped(p) && !identical(t, p)) { report(a, "invalid argument: mismatched types ${render(p)} (previous argument) and ${render(t)} (type of ${exprText(a)})", "mismatched-types"); return }
                    if (p != null && untypedKindsMismatch(p, t)) { report(a, "invalid argument: mismatched types $p (previous argument) and $t (type of ${exprText(a)})", "mismatched-types"); return }
                    // An untyped constant argument converts to the typed argument's type (every term of a type parameter's type set must accept it).
                    fun convertUntyped(arg: GoExpression, ut: GoType, target: GoType): Boolean {
                        val uk = (ut as GoBasicType).kind
                        if (target is GoTypeParamType) {
                            val terms = target.terms
                            if (terms != null && terms.any { term -> (term.type.underlying() as? GoBasicType)?.let { !GoTypePredicates.representableKind(uk, it.kind) } ?: true }) {
                                report(a, "invalid argument: mismatched types ${ut} (previous argument) and ${render(t)} (type of ${exprText(a)})", "mismatched-types"); return false
                            }
                            return true
                        }
                        val tb = target.underlying() as? GoBasicType ?: return true
                        if (!GoTypePredicates.representableKind(uk, tb.kind)) { report(arg, "invalid argument: mismatched types ${ut} (previous argument) and ${render(target)} (type of ${exprText(a)})", "mismatched-types"); return false }
                        val c = typer.constantOf(arg) ?: return true
                        when (representabilityFailure(c, tb.kind)) {
                            "truncated" -> { report(arg, "${describe(arg)} truncated to ${render(target)}", "representability"); return false }
                            "overflows" -> { report(arg, "${describe(arg)} overflows ${render(target)}", "representability"); return false }
                        }
                        return true
                    }
                    if (p != null && isUntyped(p) && !isUntyped(t)) { if (!convertUntyped(previousArg!!, p, t)) return }
                    if (p != null && !isUntyped(p) && isUntyped(t)) { if (!convertUntyped(a, t, p)) return }
                    if (p == null || isUntyped(p) && !isUntyped(t)) { previous = t; previousArg = a }
                }
            }
            "complex" -> {
                val x = exprArg(0) ?: return
                val y = exprArg(1) ?: return
                val xt = typer.typeOf(x)
                val yt = typer.typeOf(y)
                if (!isKnown(xt) || !isKnown(yt) || xt is GoTypeParamType || yt is GoTypeParamType) return
                val xu = isUntyped(xt)
                val yu = isUntyped(yt)
                fun isFloatKind(t: GoType) = (t.underlying() as? GoBasicType)?.kind?.isFloat == true
                fun isNumericUntyped(t: GoType) = (t as? GoBasicType)?.kind?.isNumeric == true
                when {
                    xu && yu -> if (!isNumericUntyped(xt) || !isNumericUntyped(yt)) report(x, "invalid operation: ${exprText(call)} (mismatched types ${xt} and ${yt})", "mismatched-types")
                    xu -> { if (!isFloatKind(yt)) report(y, "invalid argument: arguments have type ${render(yt)}, expected floating-point", "builtin-arg") else checkAssignable(x, yt, "argument to complex") }
                    yu -> { if (!isFloatKind(xt)) report(x, "invalid argument: arguments have type ${render(xt)}, expected floating-point", "builtin-arg") else checkAssignable(y, xt, "argument to complex") }
                    !identical(xt, yt) -> report(x, "invalid operation: ${exprText(call)} (mismatched types ${render(xt)} and ${render(yt)})", "mismatched-types")
                    !isFloatKind(xt) -> report(x, "invalid argument: arguments have type ${render(xt)}, expected floating-point", "builtin-arg")
                }
            }
            "real", "imag" -> {
                val a = exprArg(0) ?: return
                val t = typer.typeOf(a)
                if (!isKnown(t) || t is GoTypeParamType) return
                val k = (t.underlying() as? GoBasicType)?.kind
                if (isUntyped(t)) { if (k?.isNumeric != true) report(a, "invalid argument: argument has type ${t}, expected complex type", "builtin-arg") }
                else if (k?.isComplex != true) report(a, "invalid argument: argument has type ${render(t)}, expected complex type", "builtin-arg")
            }
            "copy" -> {
                val dst = exprArg(0) ?: return
                val src = exprArg(1) ?: return
                val dt = typer.typeOf(dst)
                val st = typer.typeOf(src)
                if (!isKnown(dt) || !isKnown(st)) return
                checkCopy(dst, dt, src, st)
            }
        }
    }

    /** `unsafe.Sizeof/Alignof/Offsetof/Add/Slice/SliceData/String/StringData`: arity and operand kinds. */
    private fun checkUnsafeCall(name: String, call: GoCallExpr) {
        val args = call.arguments
        val range = when (name) { "Sizeof", "Alignof", "Offsetof", "SliceData", "StringData" -> 1..1; "Add", "Slice", "String" -> 2..2; else -> return }
        if (args.size !in range) {
            report(call, if (args.size < range.first) "not enough arguments for ${exprText(call)} (expected ${range.first}, found ${args.size})" else "too many arguments for ${exprText(call)} (expected ${range.last}, found ${args.size})", "builtin-arity")
            return
        }
        if (call.argumentList?.hasEllipsis == true) { report(call, "invalid operation: invalid use of ... with built-in unsafe.$name", "builtin-arity"); return }
        if (name == "Offsetof") {
            // The operand must be a selector denoting a struct field (go/types: "not a selector expression" comes first).
            val x = args[0]
            val sel = (x as? GoExpression)?.let { unparen(it) } as? GoReferenceExpression
            val r = sel?.let { resolver.resolveReferenceExpression(it).firstOrNull() }
            if (sel?.qualifier == null || r !is GoResolver.Result.Selection) {
                val desc = if (x is GoExpression && !typer.isTypeExpression(x) && typer.typeOf(x).let { it !is GoTupleType }) describe(x) else exprText(x)
                report(x, "invalid argument: $desc is not a selector expression", "builtin-arg")
                return
            }
            val s = r.selection
            if (s is GoLookup.Selection.Method) { report(x, "invalid argument: ${exprText(x)} is a method value", "builtin-arg"); return }
            if (s is GoLookup.Selection.Field && s.path.any { it.embedded && it.type is GoPointerType }) report(x, "invalid argument: field ${sel.referenceName} is embedded via a pointer in ${describe(sel.qualifier!!)}", "builtin-arg")
            return
        }
        for (a in args) {
            if (a !is GoExpression) { report(a, "${a.text} (type) is not an expression", "not-expression"); return }
            if (typer.isTypeExpression(a)) { report(a, "${exprText(a)} (type) is not an expression", "not-expression"); return }
            val t = typer.typeOf(a)
            if (t is GoTupleType) {
                if (t.types.isEmpty()) report(a, "${exprText(a)} (no value) used as value", "no-value")
                else if (args.size == 1) report(call, "too many arguments for ${exprText(call)} (expected 1, found ${t.types.size})", "builtin-arity")
                else report(a, "multiple-value ${exprText(a)} (value of type ${render(t)}) in single-value context", "multiple-value")
                return
            }
        }
        val x = args[0] as GoExpression
        val xt = typer.typeOf(x)
        when (name) {
            "Slice" -> {
                if (isNilLiteral(x)) report(x, "invalid argument: nil is not a pointer", "builtin-arg")
                else if (isKnown(xt) && xt.underlying() !is GoPointerType) report(x, "invalid argument: ${describe(x)} is not a pointer", "builtin-arg")
                (args[1] as? GoExpression)?.let { checkIntegerArgument(it, "int", nonNegative = true) }
            }
            "String" -> {
                checkAssignable(x, GoPointerType(GoBasicType.BYTE), "argument to unsafe.String")
                (args[1] as? GoExpression)?.let { checkIntegerArgument(it, "int", nonNegative = true) }
            }
            "Sizeof", "Alignof" -> if (isNilLiteral(x)) report(x, "use of untyped nil in argument to built-in unsafe.$name", "untyped-nil")
            "SliceData" -> if (isKnown(xt) && !isNilLiteral(x) && xt.underlying() !is GoSliceType) report(x, "invalid argument: ${describe(x)} is not a slice", "builtin-arg")
            "StringData" -> checkAssignable(x, GoBasicType.STRING, "argument to unsafe.StringData")
            "Add" -> {
                if (isKnown(xt) && xt.underlying() != GoBasicType.UNSAFE_POINTER) report(x, "invalid argument: ${describe(x)} is not a unsafe.Pointer", "builtin-arg")
                (args[1] as? GoExpression)?.let { checkIntegerArgument(it, "int") }
            }
        }
    }

    // --- builtins over type sets (go/types `typeset`, `underIs`, `sliceElem`) ---

    /**
     * go/types `typeset`: the underlying types of the specific types of [t]'s type set; a type
     * parameter without specific types yields a single null (go/types calls the predicate with nil).
     * Null when a part of the type set is not known.
     */
    private fun typeSetUnderlying(t: GoType): List<GoType?>? {
        if (t !is GoTypeParamType) return listOf(t.underlying())
        if (!isKnown(t.bound)) return null
        val types = specificTypes(t) ?: return listOf(null)
        if (types.any { it is GoTypeParamType || !isKnown(it) }) return null
        return types.map { it.underlying() }
    }

    /** Untyped operands of different categories (go/types `matchTypes`: numeric, boolean and string constants never convert to each other). */
    private fun untypedKindsMismatch(a: GoType, b: GoType): Boolean {
        val x = (a as? GoBasicType)?.takeIf { it.isUntyped }?.kind ?: return false
        val y = (b as? GoBasicType)?.takeIf { it.isUntyped }?.kind ?: return false
        if (x == GoBasicKind.UNTYPED_NIL || y == GoBasicKind.UNTYPED_NIL) return false
        return x.isNumeric != y.isNumeric || x.isBoolean != y.isBoolean || x.isString != y.isString
    }

    private sealed class SliceElem {
        class Elem(val type: GoType) : SliceElem()
        class Error(val message: String) : SliceElem()
    }

    /** go/types `sliceElem`: the element type shared by the slices of [x]'s type set, or the error text; null when not sure. */
    private fun sliceElem(x: GoExpression, t: GoType, desc: String = describe(x)): SliceElem? {
        var elem: GoType? = null
        for (u in typeSetUnderlying(t) ?: return null) {
            val s = u as? GoSliceType
                ?: return SliceElem.Error(if ((t as? GoBasicType)?.kind == GoBasicKind.UNTYPED_NIL) "argument must be a slice; have untyped nil" else "argument must be a slice; have $desc")
            val prev = elem
            if (prev == null) elem = s.elem
            else if (!identical(prev, s.elem)) return if (isKnown(prev) && isKnown(s.elem)) SliceElem.Error("mismatched slice element types ${render(prev)} and ${render(s.elem)} in $desc") else null
        }
        return elem?.let { SliceElem.Elem(it) }
    }

    /** `append(f())` with a multi-value `f`: the first result is the slice operand. */
    private fun checkAppendTupleOperand(call: GoCallExpr) {
        val a = call.arguments.singleOrNull() as? GoExpression ?: return
        val first = (typer.typeOf(a) as? GoTupleType)?.types?.firstOrNull() ?: return
        if (!isKnown(first)) return
        val r = sliceElem(a, first, "1st function result (value of type ${render(first)})")
        if (r is SliceElem.Error) report(a, "invalid append: ${r.message}", "builtin-arg")
    }

    /** go/types `_Close`: every type of the operand's type set must be a channel that is not receive-only. */
    private fun checkClose(a: GoExpression, t: GoType) {
        for (u in typeSetUnderlying(t) ?: return) {
            if (u !is GoChanType) { report(a, "invalid operation: cannot close non-channel ${describe(a)}", "builtin-arg"); return }
            if (u.dir == GoChanDir.RECV) { report(a, "invalid operation: cannot close receive-only channel ${describe(a)}", "builtin-arg"); return }
        }
    }

    /** go/types `_Delete`: the key type shared by the maps of the operand's type set; null after an error or when not sure. */
    private fun deleteKeyType(m: GoExpression, t: GoType): GoType? {
        var key: GoType? = null
        for (u in typeSetUnderlying(t) ?: return null) {
            val map = u as? GoMapType
            if (map == null) { report(m, "invalid argument: ${describe(m)} is not a map", "builtin-arg"); return null }
            val k = key
            if (k != null && !identical(map.key, k)) {
                if (isKnown(k) && isKnown(map.key)) report(m, "invalid argument: maps of ${describe(m)} must have identical key types", "builtin-arg")
                return null
            }
            key = map.key
        }
        return key
    }

    /** go/types `_Copy`: slices with identical element types, or the `[]byte` / string special case. */
    private fun checkCopy(dst: GoExpression, dt: GoType, src: GoExpression, st: GoType) {
        val dstE = when (val r = sliceElem(dst, dt)) { is SliceElem.Elem -> r.type; is SliceElem.Error -> { report(dst, "invalid copy: ${r.message}", "builtin-arg"); return }; null -> return }
        val srcSet = typeSetUnderlying(st) ?: return
        val special = assignable(dt, GoSliceType(GoBasicType.BYTE)) &&
            srcSet.all { u -> u is GoSliceType && identical(u.elem, GoBasicType.BYTE) || u is GoBasicType && u.kind.isString }
        if (special) return
        val srcE = when (val r = sliceElem(src, st)) {
            is SliceElem.Elem -> r.type
            // A string source: go/types goes on with the element type byte for a better message.
            is SliceElem.Error -> if (srcSet.all { it is GoBasicType && it.kind.isString }) GoBasicType.BYTE else { report(src, "invalid copy: ${r.message}", "builtin-arg"); return }
            null -> return
        }
        if (!isKnown(dstE) || !isKnown(srcE)) return
        if (!identical(dstE, srcE)) report(dst, "invalid copy: arguments ${describe(dst)} and ${describe(src)} have different element types ${render(dstE)} and ${render(srcE)}", "builtin-arg")
    }

    /** `new(x)` operands that are neither types nor values: untyped nil, a package name. False after an error. */
    private fun checkNewOperand(a: GoExpression): Boolean {
        if (isNilLiteral(a)) { report(a, "use of untyped nil in argument to new", "untyped-nil"); return false }
        return true
    }

    // --- builtins, constants, comparisons and declarations not covered by checkElement ---

    /** Checks dispatched next to [checkElement] (kept apart so they stay independent of the generic checks). */
    private fun checkElementMore(e: PsiElement) {
        when (e) {
            is GoReferenceExpression -> { checkPackageNotInSelector(e); checkPointerToTypeParamSelector(e) }
            is GoTypeReferenceExpression -> checkQualifiedNonType(e)
            is GoConstSpec -> { checkConstType(e); checkRepeatedConstOverflow(e) }
            is GoVarSpec -> if (e.type == null && e.expressionList.size == e.varDefinitionList.size) e.expressionList.forEach { checkDefaultTypeOverflow(it, "variable declaration") }
            is GoShortVarDeclaration -> if (e.expressionList.size == e.varDefinitionList.size)
                e.varDefinitionList.forEachIndexed { i, d -> if (d.name == "_" || !isRedeclaredInSameScope(d)) checkDefaultTypeOverflow(e.expressionList[i], "assignment") }
            is GoAssignmentStatement -> {
                val lhs = e.leftHandExprList?.expressionList.orEmpty()
                if (e.assignOp?.assign != null && lhs.size == e.expressionList.size) lhs.forEachIndexed { i, l -> if (isBlank(l)) checkDefaultTypeOverflow(e.expressionList[i], "assignment to _ identifier") }
            }
            is GoLiteral -> (typer.constantOf(e) as? GoConstant.Int)?.let { if (it.value.abs().bitLength() > UNTYPED_INT_PRECISION) report(e, "constant overflow", "overflow") }
            is GoUnaryExpr -> checkComplementOverflow(e)
            is io.github.golangsupport.lang.psi.GoArrayOrSliceType -> checkArrayLength(e)
            is io.github.golangsupport.lang.psi.GoStructType -> checkDuplicateFields(e)
            is GoReturnStatement -> checkResultsInScope(e)
            is GoFunctionDeclaration -> checkMainSignature(e)
            is GoFile -> checkMethodRedeclarationsThroughAliases(e)
        }
    }

    /** go/types: a package name is only usable as the qualifier of a selector (`new(unsafe)`, `f(fmt)`). */
    private fun checkPackageNotInSelector(ref: GoReferenceExpression) {
        if (ref.qualifier != null || ref.parent !is GoArgumentList) return
        val import = resolver.resolveReferenceExpression(ref).singleOrNull() as? GoResolver.Result.Import ?: return
        report(ref, "use of package ${import.element.let { GoScopes.importName(it) }} not in selector", "not-expression")
    }

    /** go/types `interfacePtrError`: `*T` with T a type parameter has no fields or methods (`x.m` with `x *T`). */
    private fun checkPointerToTypeParamSelector(ref: GoReferenceExpression) {
        val qualifier = ref.qualifier as? GoExpression ?: return
        if (typer.isTypeExpression(qualifier)) return
        val qt = typer.typeOf(qualifier)
        if (qt !is GoPointerType || qt.elem !is GoTypeParamType) return
        if (resolver.resolveReferenceExpression(ref).none { it is GoResolver.Result.Selection }) return
        report(ref.identifier ?: ref, "${exprText(ref)} undefined (type ${render(qt)} is pointer to type parameter, not type parameter)", "undefined-member")
    }

    /** `var x math.Pi`: a qualified name used as the type of a variable, field or parameter must denote a type. */
    private fun checkQualifiedNonType(ref: GoTypeReferenceExpression) {
        val mixin = ref as? GoTypeReferenceExpressionMixin ?: return
        val qualifier = mixin.qualifierName ?: return
        val holder = ref.parent?.parent
        if (holder !is GoVarSpec && holder !is io.github.golangsupport.lang.psi.GoFieldDeclaration && holder !is io.github.golangsupport.lang.psi.GoParameterDeclaration) return
        val target = resolver.resolveTypeReference(ref) as? GoNamedElement ?: return
        if (target !is io.github.golangsupport.lang.psi.GoConstDefinition && target !is GoVarDefinition && target !is GoFunctionDeclaration) return
        val t = typer.declarationType(target, ref)
        if (!isKnown(t)) return
        val text = "$qualifier.${mixin.referenceName}"
        // go/types operand.String of the qualified identifier.
        val desc = when (target) {
            is io.github.golangsupport.lang.psi.GoConstDefinition -> typer.constantValueOf(target)?.let { c -> if (isUntyped(t)) "$t constant $c" else "constant $c of type ${render(t)}" } ?: return
            is GoVarDefinition -> "variable of ${typeDescription(t)}"
            else -> "value of ${typeDescription(t)}"
        }
        report(ref, "$text ($desc) is not a type", "not-a-type")
    }

    /** Spec "Constant declarations": a typed constant must have a boolean, numeric or string type. */
    private fun isConstType(t: GoType): Boolean =
        t !is GoTypeParamType && (t.underlying() as? GoBasicType)?.kind?.let { it.isBoolean || it.isNumeric || it.isString } == true

    /** go/types `constDecl`: `invalid constant type T`. */
    private fun checkConstType(spec: GoConstSpec) {
        val node = spec.type ?: return
        val t = typer.builder.typeOf(node)
        if (isKnown(t) && !isConstType(t)) report(node, "invalid constant type ${render(t)}", "const-type")
    }

    /** An implicitly repeated typed constant (`byte(iota + 253)` repeated) whose value overflows its type. */
    private fun checkRepeatedConstOverflow(spec: GoConstSpec) {
        if (spec.expressionList.isNotEmpty() || spec.type != null) return
        val source = typer.repeatedConstSpec(spec)?.takeIf { it.expressionList.isNotEmpty() && it.type == null } ?: return
        if (source.expressionList.size != spec.constDefinitionList.size) return
        for (def in spec.constDefinitionList) {
            val t = typer.declarationType(def, def)
            val kind = (t.underlying() as? GoBasicType)?.kind ?: continue
            if (!isKnown(t) || isUntyped(t) || !kind.isInteger) continue
            val c = typer.constantValueOf(def) as? GoConstant.Int ?: continue
            if (representabilityFailure(c, kind) == "overflows") report(def, "constant ${c.render()} overflows ${render(t)}", "representability")
        }
    }

    /**
     * go/types `assignment` with a nil or interface target: an untyped numeric constant takes its
     * default type and must be representable (`var _ = 1 << 100`, `x := 1e1000`, `_ = 1 << 100`).
     */
    private fun checkDefaultTypeOverflow(v: GoExpression, context: String) {
        val t = typer.typeOf(v) as? GoBasicType ?: return
        if (!t.isUntyped || !t.kind.isNumeric) return
        val c = typer.constantOf(v) ?: return
        val dt = GoTypePredicates.defaultType(t) as? GoBasicType ?: return
        val f = representabilityFailure(c, dt.kind) ?: return
        if (f == "overflows" || f == "truncated") report(v, "cannot use ${describe(v)} as ${render(dt)} value in $context ($f)", "representability")
    }

    /** go/types `matchTypes` of two untyped numeric operands: both take the later kind of int, rune, float, complex (`1 % 1.0` is a float operation). */
    private fun untypedNumericResult(a: GoType, b: GoType): GoType {
        val x = (a as? GoBasicType)?.kind ?: return a
        val y = (b as? GoBasicType)?.kind ?: return a
        return if (x.isNumeric && y.isNumeric && y.ordinal > x.ordinal) b else a
    }

    /** [x] described after its untyped conversion to [t] (`1 (untyped float constant)` in `1 % 1.0`). */
    private fun describeUntypedAs(x: GoExpression, t: GoType): String {
        val text = exprText(x)
        val v = typer.constantOf(x)?.toString() ?: return "$text ($t value)"
        return "$text ($t constant${if (v == text) "" else " $v"})"
    }

    /** go/types `overflow`: `^x` of an untyped constant beyond the 512-bit precision. */
    private fun checkComplementOverflow(expr: GoUnaryExpr) {
        if (expr.operator !== GoTypes.XOR) return
        val operand = expr.expression ?: return
        val inner = typer.constantOf(operand) as? GoConstant.Int ?: return
        if (!isUntyped(typer.typeOf(operand)) || inner.value.abs().bitLength() > UNTYPED_INT_PRECISION) return
        if (inner.value.not().abs().bitLength() > UNTYPED_INT_PRECISION) report(expr, "constant bitwise complement overflow", "overflow")
    }

    /** go/types `arrayLength`: a constant non-negative integer representable as int. */
    private fun checkArrayLength(node: io.github.golangsupport.lang.psi.GoArrayOrSliceType) {
        val e = GoPsiUtil.children(node, GoExpression::class.java).firstOrNull() ?: return
        val x = unparen(e)
        if (x is GoReferenceExpression && x.qualifier == null) {
            val d = resolver.resolveReferenceExpression(x).singleOrNull()?.element
            if (d is GoVarDefinition || d is GoParamDefinition || d is GoReceiver || d is GoFunctionDeclaration) { report(e, "invalid array length ${exprText(e)}", "array-length"); return }
        }
        val t = typer.typeOf(e)
        if (!isKnown(t) || t is GoTupleType || typer.isTypeExpression(e)) return
        val c = typer.constantOf(e)
        if (c == null) {
            if (isDefinitelyNotConstant(e)) report(e, "array length ${describe(e)} must be constant", "array-length")
            return
        }
        if (c is GoConstant.Int && c.value.abs().bitLength() > UNTYPED_INT_PRECISION) return // reported as a constant overflow
        // Sizes are folded for gc/amd64 only (GoSizes): a padding length computed from them may differ on the real target.
        val calls = PsiTreeUtil.findChildrenOfType(e, GoCallExpr::class.java) + listOfNotNull(e as? GoCallExpr)
        if (calls.any { call -> call.expression?.let { isUnsafeCall(unparen(it)) } == true }) return
        val kind = (t.underlying() as? GoBasicType)?.kind ?: return
        val integer = kind.isInteger
        if (isUntyped(t) || integer) {
            val v = c.toBigInteger()
            if (v != null && v.signum() >= 0 && v.bitLength() < 64) return
        }
        report(e, if (integer) "invalid array length ${describe(e)}" else "array length ${describe(e)} must be integer", "array-length")
    }

    /** go/types `structType`: field names (embedded fields by their type name) are unique. */
    private fun checkDuplicateFields(struct: io.github.golangsupport.lang.psi.GoStructType) {
        val seen = HashSet<String>()
        for (decl in struct.fieldDeclarationList) {
            val names: List<PsiElement> = decl.anonymousFieldDefinition?.let { listOf(it) } ?: decl.fieldDefinitionList
            for (n in names) {
                val name = (n as? GoNamedElement)?.name ?: continue
                if (name == "_" || seen.add(name)) continue
                // An embedded field: the whole field, as its own embedded-field errors (an inner range would drop them).
                val at = if (n is GoAnonymousFieldDefinition) n else (n as? GoNamedElement)?.nameIdentifier ?: n
                report(at, "$name redeclared", "redeclared")
            }
        }
    }

    /** Spec "Return statements": a bare return needs every named result in scope (`result parameter a not in scope at return`). */
    private fun checkResultsInScope(stmt: GoReturnStatement) {
        if (stmt.expressionList.isNotEmpty()) return
        val sig = when (val owner = GoPsiUtil.functionOwner(stmt)) {
            is GoFunctionDeclaration -> owner.signature
            is GoMethodDeclaration -> owner.signature
            is GoFunctionLit -> owner.signature
            else -> null
        } ?: return
        for (d in sig.result?.parameters?.parameterDeclarationList.orEmpty()) for (def in d.paramDefinitionList) {
            val name = def.name ?: continue
            if (name == "_") continue
            val found = GoScopes.resolveName(stmt, name).firstOrNull()?.element ?: continue
            if (found == def || found.isEquivalentTo(def) || reusesResult(found, stmt)) continue
            report(stmt, "result parameter $name not in scope at return", "return-scope"); return
        }
    }

    /**
     * `m, err := w.Write(b)` in the outermost block of the function reuses the result `err`: parameters, results and that block are one
     * scope (spec "Declarations and scope"), so `:=` there does not shadow (seen on GOROOT: 119 bare returns after such a `:=`).
     */
    private fun reusesResult(found: PsiElement, stmt: GoReturnStatement): Boolean {
        val decl = PsiTreeUtil.getParentOfType(found, GoShortVarDeclaration::class.java, true, GoBlock::class.java) ?: return false
        val block = PsiTreeUtil.getParentOfType(decl, GoBlock::class.java) ?: return false
        return isOwnBody(block) && block.parent === GoPsiUtil.functionOwner(stmt)
    }

    /** go/types `collectObjects`: `func main` (package main) and `func init` take no arguments and return nothing; `init` needs a body. */
    private fun checkMainSignature(decl: GoFunctionDeclaration) {
        val name = decl.name ?: return
        if (name == "init" && decl.block == null) report(decl.nameIdentifier ?: decl, "func init must have a body", "init-signature")
        if (name != "main" || file.packageName != "main") return
        val sig = decl.signature ?: return
        if (decl.typeParameters != null) report(decl.nameIdentifier ?: decl, "func main must have no type parameters", "init-signature")
        if (sig.parameters?.parameterDeclarationList?.isNotEmpty() == true || sig.result != null) report(decl.nameIdentifier ?: decl, "func main must have no arguments and no return values", "init-signature")
    }

    /** Methods declared twice on one type through different alias names (`func (T) m()` and `func (A) m()` with `type A = T`). */
    private fun checkMethodRedeclarationsThroughAliases(f: GoFile) {
        val seen = HashMap<Pair<PsiElement, String>, GoMethodDeclaration>()
        for (m in f.methods) {
            val name = m.name ?: continue
            if (name == "_") continue
            val rt = m.receiver?.type?.let { typer.builder.typeOf(it) } ?: continue
            val base = ((rt as? GoPointerType)?.elem ?: rt) as? GoNamedType ?: continue
            val prev = seen.putIfAbsent(base.declaration to name, m) ?: continue
            // Same spelling: reported by checkPackageRedeclarations.
            if (prev.receiverTypeName == m.receiverTypeName) continue
            report(m.nameIdentifier ?: m, "method ${base.declaration.name}.$name already declared", "redeclared")
        }
    }

    /**
     * go/types `comparison` with type parameters: `==` / `!=` of two operands of the same type
     * parameter needs a comparable type set (`incomparable types in type set`, `empty type set`).
     */
    private fun checkTypeParamEquality(left: GoExpression, right: GoExpression, op: String, at: PsiElement, lt: GoType, rt: GoType) {
        if (op != "==" && op != "!=") return
        if (lt !is GoTypeParamType || !identical(lt, rt) || !isKnown(lt.bound) || comparable(lt)) return
        val cause = if (lt.terms?.isEmpty() == true) "empty type set" else "incomparable types in type set"
        report(at, "invalid operation: ${exprText(left)} $op ${exprText(right)} ($cause)", "operator")
    }

    /**
     * An untyped constant against a non-empty interface (go/types `matchTypes` does not convert a
     * numeric constant to an interface): `i == 0 (mismatched types interface{m() int} and untyped int)`.
     */
    private fun checkUntypedInterfaceOperand(untyped: GoType, typed: GoType, at: PsiElement): Boolean {
        val bin = at as? GoBinaryExpr ?: return true
        val uk = (untyped as? GoBasicType)?.kind ?: return true
        if (typed is GoTypeParamType || !uk.isNumeric || assignable(untyped, typed)) return true
        val l = bin.left ?: return true
        val r = bin.right ?: return true
        val op = opText(bin.operator) ?: return true
        val (lt, rt) = if (isUntyped(typer.typeOf(l))) untyped.toString() to render(typed) else render(typed) to untyped.toString()
        val comparison = op in setOf("==", "!=", "<", "<=", ">", ">=")
        report(if (comparison) r else bin, "invalid operation: ${exprText(l)} $op ${exprText(r)} (mismatched types $lt and $rt)", "mismatched-types")
        return false
    }

    /** An index, size or shift-count operand: untyped constants convert to [target]; typed operands must be integers. */
    private fun checkIntegerArgument(a: GoExpression, target: String, nonNegative: Boolean = false): Boolean {
        if (typer.isTypeExpression(a)) { report(a, "${exprText(a)} (type) is not an expression", "not-expression"); return false }
        if (isNilLiteral(a)) { report(a, "cannot convert nil to type $target", "conversion"); return false }
        if (nonNegative) typer.constantOf(a)?.toBigInteger()?.let { if (it.signum() < 0) { report(a, "invalid argument: index ${exprText(a)} (constant of type int) must not be negative", "index"); return false } }
        val at = typer.typeOf(a)
        if (at is GoTupleType) { report(a, if (at.types.isEmpty()) "${exprText(a)} (no value) used as value" else "multiple-value ${exprText(a)} (value of type ${render(at)}) in single-value context", "no-value"); return false }
        if (!isKnown(at) || at is GoTypeParamType) return true
        val c = typer.constantOf(a)
        if (isUntyped(at) && c != null) {
            val k = (at as GoBasicType).kind
            if (k == GoBasicKind.UNTYPED_BOOL || k == GoBasicKind.UNTYPED_STRING || k == GoBasicKind.UNTYPED_NIL) { report(a, "cannot convert ${describe(a)} to type $target", "conversion"); return false }
            if (target == "uint" && c.toBigInteger()?.signum() == -1) { report(a, "invalid operation: negative shift count ${describe(a)}", "operator"); return false }
            // A shift or other operation reports its own overflow (checkShift / checkBinaryOperands).
            if (c is GoConstant.Int && c.value.bitLength() > UNTYPED_INT_PRECISION) { if (unparen(a) !is GoBinaryExpr) report(a, "constant shift overflow", "overflow"); return false }
            val f = representabilityFailure(c, if (target == "uint") GoBasicKind.UINT else GoBasicKind.INT)
            if (f != null) {
                if (target == "uint") report(a, "invalid operation: shift count ${describe(a)} ${if (f == "truncated") "truncated to" else "overflows"} uint", "operator")
                else report(a, when (f) { "truncated" -> "${describe(a)} truncated to int"; "overflows" -> "${describe(a)} overflows int"; else -> "cannot convert ${describe(a)} to type int" }, "conversion")
                return false
            }
            return true
        }
        if (isUntyped(at) && at !is GoTypeParamType) {
            // A non-constant untyped expression (`1<<s + 1.2`) takes the type int: its constant leaves must fit.
            if (!isIntegerLike(at)) { report(a, "invalid argument: ${describe(a)} must be integer", "builtin-arg"); return false }
            return checkUntypedLeaves(a, if (target == "uint") GoBasicType.UINT else GoBasicType.INT)
        }
        if (!isIntegerLike(at)) { report(a, "invalid argument: ${describe(a)} must be integer", "builtin-arg"); return false }
        return true
    }

    /**
     * go/types `updateExprType`: when an untyped non-constant expression gets a typed context,
     * every untyped constant operand inside it must be representable in that type.
     */
    private fun checkUntypedLeaves(e: GoExpression, target: GoType): Boolean {
        val tb = target.underlying() as? GoBasicType ?: return true
        val x = unparen(e)
        val c = typer.constantOf(x)
        if (c != null) {
            val t = typer.typeOf(x)
            if (!isUntyped(t)) return true
            if (!GoTypePredicates.representableKind((t as GoBasicType).kind, tb.kind)) { report(x, "cannot convert ${describe(x)} to type ${render(target)}", "conversion"); return false }
            when (representabilityFailure(c, tb.kind)) {
                "truncated" -> { report(x, "${describe(x)} truncated to ${render(target)}", "representability"); return false }
                "overflows" -> { report(x, "${describe(x)} overflows ${render(target)}", "representability"); return false }
            }
            return true
        }
        return when (x) {
            is GoBinaryExpr -> {
                val op = opText(x.operator)
                // A non-constant shift validates its untyped constant operand against the same context type (checkShift).
                if (op == "<<" || op == ">>") true
                else if (op in setOf("==", "!=", "<", "<=", ">", ">=", "&&", "||")) true
                else (x.left?.let { checkUntypedLeaves(it, target) } ?: true) && (x.right?.let { checkUntypedLeaves(it, target) } ?: true)
            }
            is GoUnaryExpr -> x.expression?.let { checkUntypedLeaves(it, target) } ?: true
            else -> true
        }
    }

    private fun isIntegerLike(t: GoType): Boolean {
        val b = t.underlying() as? GoBasicType ?: return t is GoTypeParamType
        return b.kind.isInteger || b.kind == GoBasicKind.UNTYPED_FLOAT || b.kind == GoBasicKind.UNTYPED_RUNE
    }

    private fun checkConversionExpr(expr: GoConversionExpr) {
        val target = expr.type?.let { typer.builder.typeOf(it) } ?: return
        val args = GoPsiUtil.children(expr, GoExpression::class.java)
        checkConversionArgs(expr, target, args, expr.type?.text ?: "")
    }

    private fun checkConversion(call: GoCallExpr, target: GoType, args: List<PsiElement>) {
        checkConversionArgs(call, target, args.filterIsInstance<GoExpression>(), call.expression?.text ?: "")
    }

    private fun checkConversionArgs(at: PsiElement, target: GoType, args: List<GoExpression>, typeText: String) {
        if (!isKnown(target)) return
        if (args.isEmpty()) { report(at, "missing argument in conversion to ${render(target)}", "conversion"); return }
        if (args.size > 1) { report(args[1], "too many arguments in conversion to ${render(target)}", "conversion"); return }
        val arg = args[0]
        val vt = typer.typeOf(arg)
        if (!isKnown(vt)) return
        val c = typer.constantOf(arg)
        if (c != null && target is GoTypeParamType) {
            // go/types: a constant is convertible to a type parameter if it is convertible to each specific type.
            val terms = specificTypes(target)
            val cause = if (terms.isNullOrEmpty()) "${target.name} does not contain specific types" else terms.firstNotNullOfOrNull { term ->
                val u = term.underlying()
                val vk = (vt.underlying() as? GoBasicType)?.kind
                val uk = (u as? GoBasicType)?.kind
                when {
                    vk != null && vk.isString && u is GoSliceType && (u.elem.underlying() as? GoBasicType)?.kind.let { it == GoBasicKind.UINT8 || it == GoBasicKind.INT32 } -> null
                    uk != null && (if (uk.isString && c is GoConstant.Int) null else representabilityFailure(c, uk)) == null && (GoTypePredicates.representableKind(vk ?: uk, uk) || vk != null && vk.isInteger && uk.isString || !isUntyped(vt) && (vk?.isNumeric == true && uk.isNumeric || GoTypePredicates.convertible(vt, u))) -> null
                    vk != null && vk.isInteger && uk != null && uk.isInteger -> "constant ${c.render()} overflows ${render(u)} (in ${target.name})"
                    else -> "cannot convert ${describe(arg)} to type ${render(u)} (in ${target.name})"
                }
            }
            if (cause != null) report(arg, "cannot convert ${describe(arg)} to type ${render(target)}: $cause", "conversion")
            return
        }
        if (c != null && isUntyped(vt)) {
            val tb = target.underlying() as? GoBasicType
            if (tb != null) {
                val f = if (tb.kind.isString && c is GoConstant.Int) null else representabilityFailure(c, tb.kind)
                if (f != null) report(arg, "cannot convert ${describe(arg)} to type ${render(target)}${if (f == "not representable") "" else " ($f)"}", "conversion")
                else if (!GoTypePredicates.representableKind((vt as GoBasicType).kind, tb.kind) && !(vt.kind.isInteger && tb.kind.isString)) report(arg, "cannot convert ${describe(arg)} to type ${render(target)}", "conversion")
                return
            }
        }
        // A typed numeric constant converts to any numeric type that represents it (representability is not checked here).
        if (c != null && (vt.underlying() as? GoBasicType)?.kind?.isNumeric == true && (target.underlying() as? GoBasicType)?.kind?.isNumeric == true) return
        val cause = conversionCause(vt, target)
        if (cause != null) report(arg, "cannot convert ${describe(arg)} to type ${render(target)}${if (cause.isEmpty()) "" else ": $cause"}", "conversion")
    }

    /**
     * go/types `coreType` with the reason it does not exist: the single underlying type of every
     * specific type (channels may differ in direction if they agree on one), or a cause in go/types
     * wording ("no core type", "[]int and chan int have different underlying types", ...).
     */
    private fun coreTypeOrCause(p: GoTypeParamType): Pair<GoType?, String> {
        val types = specificTypes(p) ?: return null to "no core type"
        var core: GoType? = null
        for (t in types) {
            val u = t.underlying()
            val c = core
            if (c == null) { core = u; continue }
            if (identical(c, u)) continue
            if (c is GoChanType && u is GoChanType) {
                if (!identical(c.elem, u.elem)) return null to "channels ${render(c)} and ${render(u)} have different element types"
                if (c.dir != GoChanDir.BOTH && u.dir != GoChanDir.BOTH && c.dir != u.dir) return null to "channels ${render(c)} and ${render(u)} have conflicting directions"
                if (c.dir == GoChanDir.BOTH) core = u
                continue
            }
            return null to "${render(c)} and ${render(u)} have different underlying types"
        }
        return core to ""
    }

    /**
     * The specific types of a type parameter's type set with nested constraint interfaces
     * (`Integer | ~string` -> `int, int8, ..., string`) flattened; a non-interface constraint is
     * its own single specific type. Null when the type set has no specific types.
     */
    private fun specificTypes(p: GoTypeParamType): List<GoType>? {
        val b = p.bound
        val u = b.underlying()
        if (u !is GoInterfaceType) return if (isKnown(b)) listOf(b) else null
        val out = ArrayList<GoType>()
        fun flatten(terms: List<GoTerm>, depth: Int) {
            for (t in terms) {
                val tu = t.type.underlying()
                val nested = (tu as? GoInterfaceType)?.typeTerms
                if (t.type !is GoTypeParamType && nested != null && depth < 8) flatten(nested, depth + 1) else out += t.type
            }
        }
        flatten(u.typeTerms ?: return null, 0)
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * go/types `convertibleTo` with type parameters: a conversion involving type parameters is valid
     * when it is valid for every pair of specific types of their type sets. Returns null when [v]
     * converts to [t], otherwise the cause (empty when go/types gives none).
     */
    private fun conversionCause(v: GoType, t: GoType, depth: Int = 0): String? {
        val vp = v as? GoTypeParamType
        val tp = t as? GoTypeParamType
        if (vp == null && tp == null) return if (GoTypePredicates.convertible(v, t)) null else ""
        if (depth > 4 || GoTypePredicates.assignable(v, t)) return null
        fun join(msg: String, inner: String) = if (inner.isEmpty()) msg else "$msg\n\t$inner"
        fun specific(p: GoTypeParamType) = specificTypes(p)
        if (vp != null && tp != null) {
            val vs = specific(vp) ?: return ""
            val ts = specific(tp) ?: return ""
            for (a in vs) for (b in ts) conversionCause(a, b, depth + 1)?.let { return join("cannot convert ${render(a)} (in ${vp.name}) to type ${render(b)} (in ${tp.name})", it) }
            return null
        }
        if (vp != null) {
            val vs = specific(vp) ?: return ""
            for (a in vs) conversionCause(a, t, depth + 1)?.let { return join("cannot convert ${render(a)} (in ${vp.name}) to type ${render(t)}", it) }
            return null
        }
        val ts = specific(tp!!) ?: return ""
        for (b in ts) conversionCause(v, b, depth + 1)?.let { return join("cannot convert ${render(v)} to type ${render(b)} (in ${tp.name})", it) }
        return null
    }

    // --- operators ---

    private fun checkBinary(expr: GoBinaryExpr) {
        val left = expr.left ?: return
        val right = expr.right ?: return
        val op = opText(expr.operator) ?: return
        checkBinaryOperands(left, right, op, expr)
    }

    private fun checkBinaryOperands(left: GoExpression, right: GoExpression, op: String, at: PsiElement) {
        if (checkNonGeneric(left) or checkNonGeneric(right)) return
        val lt = typer.typeOf(left)
        val rt = typer.typeOf(right)
        for ((e, t) in listOf(left to lt, right to rt)) if (t is GoTupleType) {
            report(e, if (t.types.isEmpty()) "${exprText(e)} (no value) used as value" else "multiple-value ${exprText(e)} (value of type ${render(t)}) in single-value context", "multiple-value")
            return
        }
        if (!isKnown(lt) || !isKnown(rt)) return
        if (op == "<" || op == "<=" || op == ">" || op == ">=") {
            // go/types `comparison`: every type in the type set must be ordered.
            val bad = listOf(lt, rt).firstOrNull { t -> t is GoTypeParamType && specificTypes(t)?.all { (it.underlying() as? GoBasicType)?.kind?.isOrdered == true } != true }
            if (bad != null) { report(at, "invalid operation: ${exprText(left)} $op ${exprText(right)} (type parameter ${render(bad)} cannot use operator $op)", "operator"); return }
        }
        if (lt is GoTypeParamType || rt is GoTypeParamType) { checkTypeParamEquality(left, right, op, at, lt, rt); return }
        val text by lazy(LazyThreadSafetyMode.NONE) { "${exprText(left)} $op ${exprText(right)}" } // only for messages: exprText is linear in the subtree
        if (isUntyped(lt) && isUntyped(rt) && op != "<<" && op != ">>") {
            // Untyped constants of different categories never mix (`"" + 1`, `true == 0`).
            fun category(k: GoBasicKind) = when { k.isBoolean -> 0; k.isString -> 1; k.isNumeric -> 2; else -> 3 }
            val lk = (lt as GoBasicType).kind; val rk = (rt as GoBasicType).kind
            if (lk != GoBasicKind.UNTYPED_NIL && rk != GoBasicKind.UNTYPED_NIL && category(lk) != category(rk)) {
                report(at, "invalid operation: $text (mismatched types ${lt} and ${rt})", "mismatched-types")
                return
            }
        }
        if (op == "<<" || op == ">>") {
            checkShift(left, right, op, at, lt, rt)
            return
        }

        if (op == "&&" || op == "||") {
            if (!isBoolean(lt)) report(at, "invalid operation: operator $op not defined on ${describe(left)}", "operator")
            else if (!isBoolean(rt)) report(at, "invalid operation: operator $op not defined on ${describe(right)}", "operator")
            else if (!isUntyped(lt) && !isUntyped(rt) && !identical(lt, rt)) report(at, "invalid operation: $text (mismatched types ${render(lt)} and ${render(rt)})", "mismatched-types")
            return
        }
        val folded = (at as? GoExpression)?.let { typer.constantOf(it) }
        if (folded is GoConstant.Int && isUntyped(lt) && isUntyped(rt) && folded.value.abs().bitLength() > UNTYPED_INT_PRECISION) {
            val opName = when (op) { "*" -> "multiplication"; "+" -> "addition"; "-" -> "subtraction"; "^" -> "bitwise XOR"; "|" -> "bitwise OR"; "&" -> "bitwise AND"; "&^" -> "bitwise AND-NOT"; else -> op }
            report(at, "constant $opName overflow", "overflow")
            return
        }
        val comparison = op in setOf("==", "!=", "<", "<=", ">", ">=")
        // Operand types must match (after untyped conversion).
        val lu = isUntyped(lt)
        val ru = isUntyped(rt)
        if (!lu && !ru && !identical(lt, rt)) {
            if (comparison && (assignable(lt, rt) || assignable(rt, lt))) {
                // Mixed interface/concrete comparisons are fine when assignable.
            } else {
                report(at, "invalid operation: $text (mismatched types ${render(lt)} and ${render(rt)})", "mismatched-types")
                return
            }
        }
        if (lu && !ru) { if (!checkUntypedOperand(left, lt, rt, at)) return }
        if (ru && !lu) { if (!checkUntypedOperand(right, rt, lt, at)) return }
        val t = if (!lu) lt else if (!ru) rt else untypedNumericResult(lt, rt)
        if ((op == "/" || op == "%") && typer.constantOf(right)?.let { c -> c.toBigDecimal()?.signum() == 0 } == true) {
            val k = (t.underlying() as? GoBasicType)?.kind
            if (k != null && (k.isInteger || op == "/" && (isUntyped(t) || typer.constantOf(left) != null))) {
                report(right, "invalid operation: division by zero", "operator")
                return
            }
        }
        if (comparison) {
            // go/types `comparison`: the error is reported at the first operand that is not
            // comparable (==, !=) or not ordered (<, <=, >, >=); mixed interface/concrete operands
            // are checked individually (`e == s` with s a slice).
            fun isNil(x: GoType) = (x as? GoBasicType)?.kind == GoBasicKind.UNTYPED_NIL
            fun errAt(operand: GoExpression): PsiElement = if (operand === left) at else operand
            if (op == "==" || op == "!=") {
                if (t is GoBasicType && t.kind == GoBasicKind.UNTYPED_NIL) { if (lt == rt) report(at, "invalid operation: $text (operator $op not defined on untyped nil)", "operator"); return }
                if (isNil(lt) || isNil(rt)) return
                val bad = when { !comparable(lt) -> left to lt; !comparable(rt) -> right to rt; else -> null }
                if (bad != null) report(errAt(bad.first), "invalid operation: $text (${incomparableReason(bad.second)})", "operator")
            } else {
                fun ordered(x: GoType) = (x.underlying() as? GoBasicType)?.kind?.isOrdered == true
                val bad = when { !ordered(lt) -> left to lt; !ordered(rt) -> right to rt; else -> null }
                if (bad != null) report(errAt(bad.first), "invalid operation: $text (operator $op not defined on ${kindString(bad.second)})", "operator")
            }
            return
        }
        val b = t.underlying() as? GoBasicType
        val defined = when (op) {
            "+" -> b != null && (b.kind.isNumeric || b.kind.isString)
            "-", "*", "/" -> b != null && b.kind.isNumeric
            "%", "&", "|", "^", "&^" -> b != null && b.kind.isInteger
            else -> true
        }
        if (!defined) { report(at, "invalid operation: operator $op not defined on ${if (lu && ru && t !== lt) describeUntypedAs(left, t) else describe(if (lu) right else left)}", "operator"); return }
        // A typed constant result must be representable in its type (`byte(0) - byte(1)`).
        if (folded != null && b != null && !isUntyped(t) && at is GoExpression) {
            val f = representabilityFailure(folded, b.kind)
            if (f == "overflows") report(at, "constant ${folded.render()} overflows ${render(t)}", "representability")
            else if (f == "truncated" && b.kind.isInteger) report(at, "constant ${folded.render()} truncated to ${render(t)}", "representability")
        }
    }

    /**
     * go/types `shift`: the left operand must be an integer (an untyped constant must have an
     * integer value), the count an integer or an untyped constant representable as uint. A
     * non-constant shift of an untyped constant takes the type the constant would have without
     * the shift (its context type), which must be an integer type too.
     */
    private fun checkShift(left: GoExpression, right: GoExpression, op: String, at: PsiElement, lt: GoType, rt: GoType) {
        val lc = typer.constantOf(left)
        val lk = (lt.underlying() as? GoBasicType)?.kind
        if (lk == null || !lk.isInteger && !(isUntyped(lt) && lc?.toBigInteger() != null)) {
            report(left, "invalid operation: shifted operand ${describe(left)} must be integer", "operator")
            return
        }
        val rc = typer.constantOf(right)
        if (isUntyped(rt) && rc != null) { if (!checkIntegerArgument(right, "uint")) return }
        else if (isUntyped(rt)) {
            // A non-constant untyped count (`1.<<s` as a count) is converted to uint; its own shift check validates the constant.
        } else {
            val rk = (rt.underlying() as? GoBasicType)?.kind
            if (rk == null || !rk.isInteger) {
                report(right, "invalid operation: shift count ${describe(right)} must be integer", "operator")
                return
            }
        }
        if (lc != null && rc != null) {
            // Constant shift: the count is bounded (go/types shiftBound = 1023), the result must fit the untyped precision or the typed operand.
            val n = rc.toBigInteger() ?: return
            if (n.bitLength() > 10 || n.toInt() > 1023) { report(right, "invalid shift count ${describe(right)}", "operator"); return }
            val x = lc.toBigInteger() ?: return
            if (op == "<<" && isUntyped(lt) && x.signum() != 0 && x.bitLength() + n.toInt() > UNTYPED_INT_PRECISION) { report(at, "constant shift overflow", "overflow"); return }
            if (!isUntyped(lt)) {
                val folded = (at as? GoExpression)?.let { typer.constantOf(it) } ?: return
                representabilityFailure(folded, lk)?.let { report(at, "constant ${folded.render()} overflows ${render(lt)}", "representability") }
            }
            return
        }
        if (lc != null && isUntyped(lt)) {
            // Non-constant shift of an untyped constant: the constant gets its context type.
            // An interface context (`fmt.Printf("%x", 1<<i)`) gives the constant its default type.
            val ctx = (at as? GoExpression)?.let { untypedContextType(it, (lt as GoBasicType).kind) }
                ?.let { if (it !is GoTypeParamType && it.underlying() is GoInterfaceType) GoTypePredicates.defaultType(lt) else it } ?: return
            if (!isKnown(ctx) || ctx is GoTypeParamType) return
            val ck = (ctx.underlying() as? GoBasicType)?.kind
            val lk0 = (lt as GoBasicType).kind
            if (ck != null && !GoTypePredicates.representableKind(lk0, ck) && !(ck.isString && lk0.isInteger)) { report(left, "cannot convert ${describe(left)} to type ${render(ctx)}", "conversion"); return }
            if (ck == null || !ck.isInteger) { report(left, "invalid operation: shifted operand ${exprText(left)} (type ${render(ctx)}) must be integer", "operator"); return }
            when (representabilityFailure(lc, ck)) {
                "overflows" -> report(left, "${describe(left)} overflows ${render(ctx)}", "representability")
                "truncated" -> report(left, "${describe(left)} truncated to ${render(ctx)}", "representability")
                null -> {}
                else -> report(left, "cannot use ${describe(left)} as ${render(ctx)} value", "representability")
            }
        }
    }

    /**
     * go/types `updateExprType`: the type an untyped expression finally takes from its context
     * (the other operand, the declared variable type, the parameter or result type, ...), or the
     * default type of [kind] when nothing fixes it.
     */
    private fun untypedContextType(e: GoExpression, kind: GoBasicKind): GoType {
        val parent = e.parent
        val default = GoTypePredicates.defaultType(GoBasicType.of(kind))
        return when (parent) {
            is GoParenthesesExpr -> untypedContextType(parent, kind)
            is GoUnaryExpr -> if (parent.operator === GoTypes.NOT) GoBasicType.BOOL else untypedContextType(parent, kind)
            is GoBinaryExpr -> {
                val op = opText(parent.operator) ?: return default
                val other = if (parent.left === e) parent.right else parent.left
                if (op == "<<" || op == ">>") return if (parent.left === e) untypedContextType(parent, kind) else GoBasicType.UINT
                if (op == "&&" || op == "||") return GoBasicType.BOOL
                val ot = other?.let { typer.typeOf(it) }
                if (ot != null && isKnown(ot) && !isUntyped(ot) && ot !is GoTupleType) return ot
                val merged = if (ot is GoBasicType && ot.isUntyped && ot.kind != GoBasicKind.UNTYPED_NIL && ot.kind.ordinal > kind.ordinal) ot.kind else kind
                if (op in setOf("==", "!=", "<", "<=", ">", ">=")) GoTypePredicates.defaultType(GoBasicType.of(merged)) else untypedContextType(parent, merged)
            }
            is GoVarSpec -> parent.type?.let { typer.builder.typeOf(it) } ?: default
            is GoConstSpec -> parent.type?.let { typer.builder.typeOf(it) } ?: GoBasicType.of(kind)
            is GoAssignmentStatement -> {
                val i = parent.expressionList.indexOf(e)
                val target = parent.leftHandExprList?.expressionList?.getOrNull(i)
                if (target == null || isBlank(target)) default else typer.typeOf(target).takeIf { isKnown(it) && !isUntyped(it) } ?: default
            }
            is GoReturnStatement -> {
                val i = parent.expressionList.indexOf(e)
                val owner = GoPsiUtil.functionOwner(parent)
                val sig = when (owner) {
                    is GoFunctionDeclaration -> typer.builder.functionType(owner)
                    is GoMethodDeclaration -> typer.builder.methodOf(owner, null)?.signature
                    is GoFunctionLit -> typer.builder.signatureOf(owner.signature, null, null)
                    else -> null
                }
                sig?.results?.getOrNull(i)?.type?.takeIf { isKnown(it) } ?: default
            }
            is GoArgumentList -> {
                val call = parent.parent as? GoCallExpr ?: return default
                val callee = call.expression ?: return default
                val i = parent.arguments.indexOf(e)
                val c = unparen(callee)
                if (c is GoReferenceExpression && c.qualifier == null && isBuiltinCallee(c)) {
                    return when (c.referenceName) {
                        "len", "cap", "make" -> if (c.referenceName == "make" && i == 0) default else GoBasicType.INT
                        "real", "imag" -> GoBasicType.COMPLEX128
                        "complex" -> GoBasicType.FLOAT64
                        "append" -> ((typer.typeOf(call.arguments.getOrNull(0) as? GoExpression ?: return default).underlying() as? GoSliceType)?.elem ?: default).takeIf { i > 0 } ?: default
                        else -> default
                    }
                }
                if (typer.isTypeExpression(callee)) return conversionContext(typer.typeOf(callee), kind)
                val sig = typer.calleeSignature(call) ?: return default
                if (sig.isGeneric) return default
                GoInference.paramTypeAt(sig, i, parent.arguments.size, parent.hasEllipsis)?.takeIf { isKnown(it) } ?: default
            }
            is GoConversionExpr -> parent.type?.let { conversionContext(typer.builder.typeOf(it), kind) } ?: default
            is GoIndexOrSliceExpr -> if (parent.expression === e) default else GoBasicType.INT
            is GoValue -> {
                val element = parent.parent as? GoElement
                val lv = element?.parent as? GoLiteralValue
                val t = lv?.let { typer.typeOfLiteralValue(it) }
                val u = t?.let { (if (it is GoTypeParamType) it.coreType else null) ?: it.underlying() }
                when (u) {
                    is GoStructType -> {
                        val key = (element.key?.expression as? GoReferenceExpression)?.referenceName
                        val f = if (key != null) u.field(key) else u.fields.getOrNull(lv.elements.indexOf(element))
                        f?.type ?: default
                    }
                    is GoArrayType -> u.elem
                    is GoSliceType -> u.elem
                    is GoMapType -> u.value
                    else -> default
                }
            }
            is GoKey -> {
                val lv = (parent.parent as? GoElement)?.parent as? GoLiteralValue
                ((lv?.let { typer.typeOfLiteralValue(it) }?.underlying() as? GoMapType)?.key) ?: GoBasicType.INT
            }
            is GoSendStatement -> (parent.leftHandExprList?.expressionList?.singleOrNull()?.let { typer.typeOf(it) }?.underlying() as? GoChanType)?.elem ?: default
            is GoExprCaseClause -> {
                val tag = (parent.parent as? GoExprSwitchStatement)?.let { GoPsiUtil.run { it.tag } }
                tag?.let { typer.typeOf(it) }?.takeIf { isKnown(it) && !isUntyped(it) } ?: default
            }
            else -> default
        }
    }

    /** go/types `conversion`: an untyped integer shifted inside `string(...)` keeps its default type (an int -> string conversion). */
    private fun conversionContext(target: GoType, kind: GoBasicKind): GoType {
        val tb = target.underlying() as? GoBasicType
        return if (tb != null && tb.kind.isString && (kind == GoBasicKind.UNTYPED_INT || kind == GoBasicKind.UNTYPED_RUNE)) GoBasicType.INT else target
    }

    /** An untyped constant operand must be representable in the other operand's type. */
    private fun checkUntypedOperand(expr: GoExpression, untyped: GoType, typed: GoType, at: PsiElement): Boolean {
        val uk = (untyped as? GoBasicType)?.kind ?: return true
        if (uk == GoBasicKind.UNTYPED_NIL) {
            val tu = typed.underlying()
            if (!(tu is GoPointerType || tu is GoSignatureType || tu is GoSliceType || tu is GoMapType || tu is GoChanType || tu is GoInterfaceType || tu == GoBasicType.UNSAFE_POINTER)) {
                report(at, "invalid operation: ${at.text} (mismatched types ${render(typed)} and untyped nil)", "mismatched-types")
                return false
            }
            return true
        }
        val tb = typed.underlying() as? GoBasicType
        if (tb == null) {
            if (typed.underlying() is GoInterfaceType) return checkUntypedInterfaceOperand(untyped, typed, at)
            report(at, "invalid operation: ${at.text} (mismatched types ${render(typed)} and ${untyped.toString()})".replace("${render(typed)} and", "${render(typed)} and"), "mismatched-types")
            return false
        }
        if (!GoTypePredicates.representableKind(uk, tb.kind)) {
            report(at, "invalid operation: ${exprText(at)} (mismatched types ${render(typed)} and ${untyped})", "mismatched-types")
            return false
        }
        val c = typer.constantOf(expr) ?: return true
        val f = representabilityFailure(c, tb.kind) ?: return true
        if (at is GoAssignmentStatement) report(expr, "cannot use ${describe(expr)} as ${render(typed)} value in assignment ($f)", "representability")
        else report(expr, "${describe(expr)} ${if (f == "truncated") "truncated to" else if (f == "overflows") "overflows" else "cannot be represented as"} ${render(tb)}", "representability")
        return false
    }

    private fun opContext(at: PsiElement): String = if (at is GoAssignmentStatement) "assignment" else "expression"

    /** go/types `kindString`: the kind of a type for operator errors. */
    private fun kindString(t: GoType): String = when (t.underlying()) {
        is GoArrayType -> "array"; is GoSliceType -> "slice"; is GoStructType -> "struct"; is GoPointerType -> "pointer"
        is GoSignatureType -> "func"; is GoInterfaceType -> if (t is GoTypeParamType) "type parameter ${render(t)}" else "interface"
        is GoMapType -> "map"; is GoChanType -> "chan"
        else -> if (isUntyped(t)) t.toString() else render(t)
    }

    private fun incomparableReason(t: GoType): String = when (val u = t.underlying()) {
        is GoSliceType -> "slice can only be compared to nil"
        is GoMapType -> "map can only be compared to nil"
        is GoSignatureType -> "func can only be compared to nil"
        is GoStructType -> "struct containing ${u.fields.firstOrNull { !comparable(it.type) }?.type?.let(::render) ?: "?"} cannot be compared"
        is GoArrayType -> "${render(t)} cannot be compared"
        else -> "${render(t)} cannot be compared"
    }

    private fun isBoolean(t: GoType): Boolean = (t.underlying() as? GoBasicType)?.kind?.isBoolean == true

    private fun checkUnary(expr: GoUnaryExpr) {
        val operand = expr.expression ?: return
        if (expr.operator === GoTypes.TILDE) {
            val ot = typer.typeOf(operand)
            val hint = if ((ot.underlying() as? GoBasicType)?.kind?.isInteger == true) " (use ^ for bitwise complement)" else ""
            report(expr, "cannot use ~ outside of interface or type constraint$hint", "operator")
            return
        }
        val t = typer.typeOf(operand)
        if (t is GoTupleType) {
            if (t.types.isEmpty()) report(operand, "${exprText(operand)} (no value) used as value", "no-value")
            else report(operand, "multiple-value ${exprText(operand)} (value of type ${render(t)}) in single-value context", "multiple-value")
            return
        }
        if (expr.operator === GoTypes.AND && t is GoTypeParamType) {
            if (!typer.isTypeExpression(operand) && unparen(operand) !is GoCompositeLit && !isAddressable(operand)) report(expr, "invalid operation: cannot take address of ${describe(operand)}", "operator")
            return
        }
        if (!isKnown(t) || t is GoTypeParamType) return
        val b = t.underlying() as? GoBasicType
        when (expr.operator) {
            GoTypes.AND -> if (!typer.isTypeExpression(operand) && unparen(operand) !is GoCompositeLit && !isAddressable(operand)) report(expr, "invalid operation: cannot take address of ${describe(operand)}", "operator")
            GoTypes.SUB, GoTypes.ADD -> {
                if (b == null || !b.kind.isNumeric) { report(expr, "invalid operation: operator ${if (expr.operator === GoTypes.SUB) "-" else "+"} not defined on ${describe(operand)}", "operator"); return }
                // A negated typed constant must still be representable (`-byte(1)`).
                val c = typer.constantOf(expr)
                if (c != null && !isUntyped(t) && representabilityFailure(c, b.kind) != null) report(expr, "${exprText(expr)} (constant ${c.render()} of type ${render(t)}) overflows ${render(t)}", "representability")
            }
            GoTypes.NOT -> if (b == null || !b.kind.isBoolean) report(expr, "invalid operation: operator ! not defined on ${describe(operand)}", "operator")
            GoTypes.XOR -> {
                if (b == null || !b.kind.isInteger) { report(expr, "invalid operation: operator ^ not defined on ${describe(operand)}", "operator"); return }
                val c = typer.constantOf(expr)
                if (c != null && !isUntyped(t) && representabilityFailure(c, b.kind) != null) report(expr, "${exprText(expr)} (constant ${c.render()} of type ${render(t)}) overflows ${render(t)}", "representability")
            }
            GoTypes.ARROW -> {
                val ch = t.underlying() as? GoChanType
                if (ch == null) report(expr, "invalid operation: cannot receive from non-channel ${describe(operand)}", "operator")
                else if (ch.dir == GoChanDir.SEND) report(expr, "invalid operation: cannot receive from send-only channel ${describe(operand)}", "operator")
            }
            GoTypes.MUL -> if (!typer.isTypeExpression(operand) && t.underlying() !is GoPointerType && t != GoBasicType.UNSAFE_POINTER) {
                if ((t as? GoBasicType)?.kind == GoBasicKind.UNTYPED_NIL) report(expr, "invalid operation: cannot indirect nil", "operator")
                else report(expr, "invalid operation: cannot indirect ${describe(operand)}", "operator")
            }
            else -> {}
        }
    }

    private fun checkIndex(expr: GoIndexOrSliceExpr) {
        val x = expr.expression ?: return
        val xt = typer.typeOf(x)
        if (!isKnown(xt)) return
        if (xt is GoTypeParamType) { checkTypeParamIndex(expr, x, xt); return }
        if (expr.isSlice) {
            val u = xt.underlying()
            val ok = u is GoSliceType || u is GoArrayType && isAddressable(x) || u is GoBasicType && u.kind.isString || u is GoPointerType && u.elem.underlying() is GoArrayType
            if (!ok) { report(expr, "cannot slice ${describe(x)}", "index"); return }
            checkSliceIndices(expr, x, u)
            return
        }
        val indices = expr.indices
        if (xt is GoSignatureType && xt.isGeneric || typer.isTypeExpression(x)) {
            // Explicit instantiation.
            val params: Int = when {
                xt is GoSignatureType -> xt.typeParams.size
                xt is GoNamedType -> typer.builder.typeParams(xt.declaration.typeParameters).size
                else -> return
            }
            if (xt is GoNamedType && xt.declaration.typeParameters == null && xt.typeArgs.isEmpty()) {
                (unparen(x) as? GoReferenceExpression)?.let { r -> resolver.resolveReferenceExpression(r).singleOrNull()?.element }
                    .let { it as? GoTypeSpec }?.let { reportNotGeneric(x, it, indices); return }
            }
            val have = indices.size
            if (xt is GoSignatureType) {
                val targs = indices.map { if (it is PsiType) typer.builder.typeOf(it) else typer.typeOf(it as GoExpression) }
                if (have > params) report(indices[params], "got $have type arguments but want $params", "type-args")
                else if (have < params) checkPartialInstantiation(expr, xt, targs, indices)
                else checkConstraints(xt.typeParams, targs, indices)
            } else if (xt is GoNamedType) {
                checkTypeArgumentCount(x, xt.name, "type", params, have)
                checkConstraints(xt.declaration, indices.map { if (it is PsiType) typer.builder.typeOf(it) else typer.typeOf(it as GoExpression) }, indices)
            }
            return
        }
        val index = indices.singleOrNull() as? GoExpression
        val u = xt.underlying()
        when (u) {
            is GoBasicType -> if (!u.kind.isString) { report(expr, "invalid operation: cannot index ${describe(x)}", "index"); return }
            is GoArrayType, is GoSliceType -> {}
            is GoPointerType -> if (u.elem.underlying() !is GoArrayType) { report(expr, "invalid operation: cannot index ${describe(x)}", "index"); return }
            is GoMapType -> { if (index != null && isKnown(u.key)) checkAssignable(index, u.key, "map index"); return }
            else -> { report(expr, "invalid operation: cannot index ${describe(x)}", "index"); return }
        }
        if (index == null) { if (indices.size > 1) report(expr, "invalid operation: more than one index", "index"); return }
        if (!checkIntegerArgument(index, "int")) return
        val c = typer.constantOf(index)?.toBigInteger() ?: return
        if (c.signum() < 0) { report(index, "invalid argument: index ${index.text} (constant of type int) must not be negative", "index"); return }
        val length = when (u) {
            is GoArrayType -> u.length
            is GoPointerType -> (u.elem.underlying() as GoArrayType).length
            is GoBasicType -> (typer.constantOf(x) as? GoConstant.Str)?.value?.toByteArray(Charsets.UTF_8)?.size?.toLong()
            else -> null
        } ?: return
        if (c >= BigInteger.valueOf(length)) report(index, "invalid argument: index $c out of bounds [0:$length]", "index")
    }

    /**
     * `f[A]` with fewer type arguments than parameters (go/types `funcInst` + `infer`, checked live
     * against the compiler). Inference first unifies each explicit argument with the core type of its
     * constraint (`S (type int) does not satisfy interface{~[]T}`) or, without a core type, requires
     * the constraint's methods (`A (type int) does not satisfy Stringer (missing method String)`); in a
     * call both carry the prefix `in call to f[A], `. As the callee of a call whose remaining parameters
     * are all inferred and whose arguments all fit, the final verification also checks the type set
     * (`string does not satisfy Number (string missing in ~int | ~float64)`). Without a call the
     * remaining parameters cannot be inferred (go/types `cannot infer B (declared at ...)`): left out.
     */
    private fun checkPartialInstantiation(expr: GoIndexOrSliceExpr, sig: GoSignatureType, targs: List<GoType>, at: List<PsiElement>) {
        val tparams = sig.typeParams
        val tset = tparams.toSet()
        val call = (expr.parent as? GoCallExpr)?.takeIf { it.expression === expr }
        val prefix = if (call != null) "in call to ${exprText(expr)}, " else ""
        val verify = call != null && callFitsInstantiation(call)
        val u = io.github.golangsupport.semantic.infer.GoUnifier(tset)
        for ((i, t) in targs.withIndex()) if (isKnown(t) && i < tparams.size) u.bindings[tparams[i]] = t
        for ((i, arg) in targs.withIndex()) {
            val p = tparams.getOrNull(i) ?: break
            if (!isKnown(arg) || GoInference.containsParams(arg, tset) || arg is GoTypeParamType) continue
            if (!isKnown(p.bound)) continue
            val terms = p.terms
            val core = p.coreType
            if (terms != null && core != null) {
                if (terms.any { !isKnown(it.type) }) continue
                val tx = if (terms.all { it.tilde }) arg.underlying() else arg
                val trial = io.github.golangsupport.semantic.infer.GoUnifier(tset)
                trial.bindings.putAll(u.bindings)
                if (!trial.unify(tx, core)) { report(at[i], "$prefix${p.name} (type ${render(arg)}) does not satisfy ${render(p.bound)}", "constraint"); continue }
            } else {
                // go/types hasAllMethods: only a method missing on the type and on its pointer gives a plain cause here.
                val methods = (p.bound.underlying() as? GoInterfaceType)?.allMethods.orEmpty()
                val set = GoLookup.methodSet(arg)
                val ptrSet = if (arg is GoPointerType) set else GoLookup.methodSet(GoPointerType(arg))
                val missing = methods.firstOrNull { m -> set.none { it.name == m.name } && ptrSet.none { it.name == m.name } }
                if (missing != null) { report(at[i], "$prefix${p.name} (type ${render(arg)}) does not satisfy ${render(p.bound)} (missing method ${missing.name})", "constraint"); continue }
            }
            if (!verify) continue
            val bound = p.bound.substitute(sig.partialSubst)
            if (!isKnown(bound) || GoInference.containsParams(bound, tset)) continue
            GoTypePredicates.satisfactionFailure(arg, bound, ::render)?.let { report(at[i], it, "constraint") }
        }
    }

    /** The call's type arguments are all inferred and every argument is assignable to its parameter. */
    private fun callFitsInstantiation(call: GoCallExpr): Boolean {
        val sig = typer.calleeSignature(call) ?: return false
        if (sig.isGeneric) return false
        val args = call.arguments.filterIsInstance<GoExpression>()
        if (args.size != call.arguments.size) return false
        val spread = call.argumentList?.hasEllipsis == true
        return args.withIndex().all { (i, a) ->
            val at = typer.typeOf(a)
            val pt = GoInference.paramTypeAt(sig, i, args.size, spread) ?: return false
            isKnown(at) && isKnown(pt) && at !is GoTupleType && assignable(at, pt)
        }
    }

    /**
     * go/types `indexExpr` on a type parameter: every type in the type set must be indexable with
     * the same key and element types (all maps, or all non-maps with identical element types).
     */
    private fun checkTypeParamIndex(expr: GoIndexOrSliceExpr, x: GoExpression, xt: GoTypeParamType) {
        if (expr.isSlice) {
            // go/types `coreString`: []byte and string terms mix into a "bytestring" core.
            val types = specificTypes(xt)
            fun isBytes(u: GoType) = u is GoSliceType && (u.elem.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8
            fun isString(u: GoType) = (u as? GoBasicType)?.kind?.isString == true
            val threeIndex = expr.node.getChildren(null).count { it.text == ":" } == 2
            if (types != null && types.all { isBytes(it.underlying()) || isString(it.underlying()) }) {
                if (threeIndex && types.any { isString(it.underlying()) }) report(expr, "invalid operation: 3-index slice of string", "index")
                return
            }
            val (core, cause) = coreTypeOrCause(xt)
            if (core == null) report(x, "cannot slice ${describe(x)}: $cause", "index")
            return
        }
        val terms = xt.terms
        if (terms == null || terms.isEmpty()) { report(expr, "invalid operation: cannot index ${describe(x)}", "index"); return }
        data class Shape(val elem: GoType, val key: GoType?, val length: Long?)
        val shapes = terms.map { term ->
            when (val u = term.type.underlying()) {
                is GoBasicType -> if (u.kind.isString) Shape(GoBasicType.BYTE, null, null) else null
                is GoArrayType -> Shape(u.elem, null, u.length)
                is GoSliceType -> Shape(u.elem, null, null)
                is GoPointerType -> (u.elem.underlying() as? GoArrayType)?.let { Shape(it.elem, null, it.length) }
                is GoMapType -> Shape(u.value, u.key, null)
                else -> null
            } ?: run { report(expr, "invalid operation: cannot index ${describe(x)}", "index"); return }
        }
        val first = shapes[0]
        if (shapes.any { (it.key == null) != (first.key == null) || !identical(it.elem, first.elem) || it.key != null && first.key != null && !identical(it.key, first.key) }) {
            report(expr, "invalid operation: cannot index ${describe(x)}", "index"); return
        }
        val index = expr.indices.singleOrNull() as? GoExpression ?: return
        if (first.key != null) { if (isKnown(first.key)) checkAssignable(index, first.key, "map index"); return }
        if (!checkIntegerArgument(index, "int", nonNegative = true)) return
        val c = typer.constantOf(index)?.toBigInteger() ?: return
        val length = shapes.mapNotNull { it.length }.minOrNull() ?: return
        if (c.compareTo(BigInteger.valueOf(length)) >= 0) report(index, "invalid argument: index $c out of bounds [0:$length]", "index")
    }

    /** go/types `sliceExpr`: index kinds, bounds (length + 1 for slices), ordering, 3-index restrictions. */
    private fun checkSliceIndices(expr: GoIndexOrSliceExpr, x: GoExpression, u: GoType) {
        // Slot of each index expression: the number of colons before it.
        val slots = arrayOfNulls<GoExpression>(3)
        val colonNodes = ArrayList<PsiElement>(2)
        var colons = 0
        var n = expr.node.firstChildNode
        while (n != null) {
            val psi = n.psi
            if (n.elementType === GoTypes.COLON) { colons++; colonNodes += psi }
            else if (psi is GoExpression && psi !== x && colons < 3) slots[colons] = psi
            n = n.treeNext
        }
        val isString = u is GoBasicType && u.kind.isString
        if (colons == 2) {
            if (isString) { report(expr, "invalid operation: 3-index slice of string", "index"); return }
            // go/types reports each missing index at the colon in front of it, both when both are missing.
            val missingMiddle = slots[1] == null
            val missingFinal = slots[2] == null
            if (missingMiddle) report(colonNodes[0], "middle index required in 3-index slice", "index")
            if (missingFinal) report(colonNodes[1], "final index required in 3-index slice", "index")
            if (missingMiddle || missingFinal) return
        }
        val length: Long? = when (u) {
            is GoArrayType -> u.length
            is GoPointerType -> (u.elem.underlying() as? GoArrayType)?.length
            is GoBasicType -> (typer.constantOf(x) as? GoConstant.Str)?.value?.toByteArray(Charsets.UTF_8)?.size?.toLong()
            else -> null
        }
        val values = LongArray(3) { -1 }
        var invalid = false
        for (i in 0..2) {
            val index = slots[i] ?: continue
            if (!checkIntegerArgument(index, "int")) { invalid = true; continue }
            val c = typer.constantOf(index)?.toBigInteger() ?: continue
            if (c.signum() < 0) { report(index, "invalid argument: index ${exprText(index)} (constant of type int) must not be negative", "index"); invalid = true; continue }
            if (length != null && c.compareTo(BigInteger.valueOf(length)) > 0) { report(index, "invalid argument: index $c out of bounds [0:${length + 1}]", "index"); invalid = true; continue }
            values[i] = if (c.bitLength() < 63) c.toLong() else Long.MAX_VALUE
        }
        if (invalid) return
        // Constant indices must be non-decreasing.
        var prev = -1L
        for (i in 0..2) {
            val v = values[i]
            if (v < 0) continue
            if (v < prev) { report(slots[i] ?: expr, "invalid slice indices: $v < $prev", "index"); return }
            prev = v
        }
    }

    /** Spec "Address operators" / go/types `assignment`: the left-hand side must be addressable or a map index expression. */
    private fun checkAssignTarget(target: GoExpression): Boolean {
        val x = unparen(target)
        if (isBlank(x)) return true
        val t = typer.typeOf(x)
        // `x[i] = v` where a specific type of x is a string (`[T []byte | string]`): the element type has no core type, the index is still a value.
        val stringIndex = x is GoIndexOrSliceExpr && !x.isSlice && (typer.typeOf(x.expression ?: x) as? GoTypeParamType)?.terms?.let { ts ->
            ts.all { isKnown(it.type) && it.type.underlying() !is GoMapType } && ts.any { t -> t.type.underlying().let { it is GoBasicType && it.kind.isString } }
        } == true
        if (stringIndex) { report(target, "cannot assign to ${exprText(x)} (neither addressable nor a map index expression)", "unassignable"); return false }
        if (t is GoUnknownType || t is GoTupleType || typer.isTypeExpression(x)) return true // reported as a value/type misuse elsewhere
        if (isAddressable(x)) return true
        if (x is GoIndexOrSliceExpr && !x.isSlice) {
            val xt = typer.typeOf(x.expression ?: return true)
            val u = (if (xt is GoTypeParamType) xt.coreType else null) ?: xt.underlying()
            if (u is GoMapType || !isKnown(xt)) return true
            if (xt is GoTypeParamType && xt.coreType == null && xt.terms?.none { t -> t.type.underlying().let { it is GoBasicType && it.kind.isString } } != false) return true
        }
        if (x is GoReferenceExpression) {
            val r = resolver.resolveReferenceExpression(x).firstOrNull() ?: return true
            if (r is GoResolver.Result.Cgo || r is GoResolver.Result.Import) return true
            val q = x.qualifier
            if (r is GoResolver.Result.Selection && r.selection is GoLookup.Selection.Field && q != null) {
                val qx = unparen(q)
                if (qx is GoIndexOrSliceExpr && !qx.isSlice && typer.typeOf(qx.expression ?: qx).underlying() is GoMapType) {
                    report(target, "cannot assign to struct field ${exprText(x)} in map", "unassignable")
                    return false
                }
                if (!isKnown(typer.typeOf(q))) return true
            }
        }
        if (!isKnown(t)) return true
        report(target, "cannot assign to ${exprText(x)} (neither addressable nor a map index expression)", "unassignable")
        return false
    }

    private fun isAddressable(e: GoExpression): Boolean {
        val x = unparen(e)
        return when (x) {
            is GoReferenceExpression -> {
                val r = resolver.resolveReferenceExpression(x).firstOrNull()
                val d = r?.element
                d is GoVarDefinition || d is GoParamDefinition || d is GoReceiver || r is GoResolver.Result.Cgo ||
                    (r is GoResolver.Result.Selection && r.selection is GoLookup.Selection.Field && (x.qualifier?.let { q -> isAddressable(q) || typer.typeOf(q).underlying() is GoPointerType || (r.selection as GoLookup.Selection.Field).indirect } ?: false))
            }
            is GoIndexOrSliceExpr -> !x.isSlice && typer.typeOf(x.expression ?: return false).let { xt ->
                val u = (if (xt is GoTypeParamType) xt.coreType else null) ?: xt.underlying()
                when {
                    u is GoMapType -> false
                    u is GoSliceType || u is GoPointerType -> true
                    // A string index is a value, never a variable (also when one specific type is a string).
                    u is GoBasicType && u.kind.isString -> false
                    xt is GoTypeParamType && xt.coreType == null -> xt.terms?.all { t -> t.type.underlying().let { it !is GoMapType && !(it is GoBasicType && it.kind.isString) } } ?: true
                    else -> isAddressable(x.expression!!)
                }
            }
            is GoUnaryExpr -> x.operator === GoTypes.MUL
            is GoCompositeLit -> false
            else -> false
        }
    }

    private fun checkTypeAssertion(expr: GoTypeAssertionExpr) {
        val x = expr.expression ?: return
        val xt = typer.typeOf(x)
        if (xt is GoTypeParamType) { report(x, "invalid operation: cannot use type assertion on type parameter value ${exprText(x)}", "type-assertion"); return }
        if (!isKnown(xt)) return
        val iface = xt.underlying() as? GoInterfaceType
        if (iface == null) { report(expr, "invalid operation: ${describe(x)} is not an interface", "type-assertion"); return }
        val target = expr.type?.let { typer.builder.typeOf(it) } ?: return
        if (!isKnown(target) || target is GoTypeParamType || target.underlying() is GoInterfaceType) return
        if (!GoTypePredicates.implements(target, iface)) {
            report(expr.type ?: expr, "impossible type assertion: ${expr.text}\n\t${render(target)} does not implement ${render(xt)} ${implementsDetail(target, iface)}", "type-assertion")
        }
    }

    private fun checkSend(stmt: GoSendStatement) {
        val ch = stmt.leftHandExprList?.expressionList?.singleOrNull() ?: return
        val value = stmt.expression ?: return
        val ct = typer.typeOf(ch)
        if (!isKnown(ct) || ct is GoTypeParamType) return
        val chan = ct.underlying() as? GoChanType
        if (chan == null) { report(stmt, "invalid operation: cannot send to non-channel ${describe(ch)}", "send"); return }
        if (chan.dir == GoChanDir.RECV) { report(stmt, "invalid operation: cannot send to receive-only channel ${describe(ch)}", "send"); return }
        if (isKnown(chan.elem)) checkAssignable(value, chan.elem, "send")
    }

    private fun checkIncDec(stmt: GoIncDecStatement) {
        val x = stmt.leftHandExprList?.expressionList?.singleOrNull() ?: return
        val t = typer.typeOf(x)
        if (!isKnown(t) || t is GoTypeParamType) return
        if (!checkAssignTarget(x)) return
        val b = t.underlying() as? GoBasicType
        if (b == null || !b.kind.isNumeric) report(stmt, "invalid operation: ${x.text}${if (stmt.inc != null) "++" else "--"} (non-numeric type ${render(t)})", "operator")
    }

    private fun checkExpressionStatement(stmt: GoSimpleStatement) {
        if (stmt.statement != null) return
        val expr = stmt.expressions.singleOrNull() ?: return
        val x = unparen(expr)
        if (x is GoUnaryExpr && x.operator === GoTypes.ARROW) return
        if (x is GoCallExpr) {
            val callee = x.expression ?: return
            val c = unparen(callee)
            if (c is GoReferenceExpression && c.qualifier == null) {
                val name = c.referenceName ?: return
                val results = resolver.resolveReferenceExpression(c)
                val builtin = results.isEmpty() && name in GoUniverse.FUNCTIONS || results.any { it.element is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(it.element as GoFunctionDeclaration) }
                if (builtin && name in setOf("len", "cap", "append", "make", "new", "complex", "real", "imag", "min", "max")) {
                    report(expr, "${describe(expr)} is not used", "unused-value")
                }
                return
            }
            if (typer.isTypeExpression(callee)) report(expr, "${describe(expr)} is not used", "unused-value")
            else if (isUnsafeCall(c)) pendingUnused += expr
            return
        }
        if (checkNonGeneric(x)) return
        if (x is GoReferenceExpression) {
            val r = resolver.resolveReferenceExpression(x)
            val builtinFn = x.qualifier == null && (r.isEmpty() && (x.referenceName ?: "") in GoUniverse.FUNCTIONS || r.any { it.element is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(it.element as GoFunctionDeclaration) })
            if (builtinFn) { report(expr, "${exprText(expr)} (built-in) must be called", "unused-value"); return }
            if (r.any { it is GoResolver.Result.Selection && it.selection is GoLookup.Selection.Method }) { report(expr, "${exprText(expr)} (value of type ${render(typer.typeOf(expr))}) must be called", "unused-value"); return }
        }
        if (x is GoConversionExpr) { report(expr, "${describe(expr)} is not used", "unused-value"); return }
        val t = typer.typeOf(expr)
        if (t is GoUnknownType && typer.constantOf(expr) == null && !(x is GoReferenceExpression && x.qualifier == null && x.referenceName == "nil")) return
        report(expr, "${describe(expr)} is not used", "unused-value")
    }

    private fun checkCondition(cond: GoExpression, where: String) {
        val t = typer.typeOf(cond)
        if (!isKnown(t) || t is GoTypeParamType) return
        if (!isBoolean(t)) report(cond, "non-boolean condition in $where", "condition")
    }

    private fun checkDeferGo(expr: GoExpression, keyword: String) {
        val x = unparen(expr)
        if (x !is GoCallExpr) { report(expr, "expression in $keyword must be function call", "defer-go"); return }
        if (x !== expr) report(expr, "expression in $keyword must not be parenthesized", "defer-go")
        val callee = x.expression?.let(::unparen) ?: return
        if (typer.isTypeExpression(callee)) { report(callee, "$keyword requires function call, not conversion", "defer-go"); return }
        val ref = callee as? GoReferenceExpression ?: return
        val discards = setOf("append", "cap", "complex", "imag", "len", "make", "max", "min", "new", "real")
        val unsafeDiscards = setOf("Add", "Alignof", "Offsetof", "Sizeof", "Slice", "SliceData", "String", "StringData")
        if (ref.qualifier == null && ref.referenceName in discards && isBuiltinCallee(ref) || isUnsafeCall(ref) && ref.referenceName in unsafeDiscards)
            report(callee, "$keyword discards result of ${exprText(x)}", "defer-go")
    }

    private fun checkRange(clause: GoRangeClause) {
        val x = clause.expression ?: return
        val t0 = typer.typeOf(x)
        if (!isKnown(t0)) return
        val t = if (t0 is GoTypeParamType) coreTypeOrCause(t0).let { (core, cause) -> core ?: run { report(x, "cannot range over ${describe(x)}: $cause", "range"); usedLocals += clause.varDefinitionList; return } } else t0
        val u = t.underlying()
        if (u is GoChanType && u.dir == GoChanDir.SEND) { report(x, "cannot range over ${describe(x)}: receive from send-only channel", "range"); usedLocals += clause.varDefinitionList; return }
        val kinds = typer.rangeTypes(t)
        val rangeable = when (u) {
            is GoBasicType -> u.kind.isString || u.kind.isInteger || (isUntyped(t) && u.kind == GoBasicKind.UNTYPED_RUNE)
            is GoArrayType, is GoSliceType, is GoMapType, is GoChanType -> true
            is GoPointerType -> u.elem.underlying() is GoArrayType
            is GoSignatureType -> kinds.isNotEmpty() || isIteratorSignature(u)
            else -> false
        }
        if (!rangeable) { report(x, "cannot range over ${describe(x)}", "range"); usedLocals += clause.varDefinitionList; return }
        clause.leftHandExprList?.expressionList?.let { lhs ->
            for ((i, target) in lhs.withIndex()) {
                if (isBlank(target) || !checkAssignTarget(target)) continue
                val k = kinds.getOrNull(i) ?: continue
                val tt = typer.typeOf(target)
                if (isKnown(tt) && isKnown(k) && !assignable(k, tt)) report(target, "cannot use ${exprText(target)} (value of type ${render(k)}) as ${render(tt)} value in assignment", "assignability")
            }
        }
        val names = clause.varDefinitionList.filter { it.name != "_" }
        if (clause.varDefinitionList.isNotEmpty() && names.isEmpty()) report(clause.varDefinitionList.last(), "no new variables on left side of :=", "short-var")
        names.groupBy { it.name }.values.filter { it.size > 1 }.forEach { dup -> dup.drop(1).forEach { report(it, "${it.name} redeclared in this block", "redeclared") } }
        val vars = clause.varDefinitionList.size.takeIf { it > 0 } ?: clause.leftHandExprList?.expressionList?.size ?: 0
        val max = when (u) {
            is GoChanType -> 1
            is GoBasicType -> if (u.kind.isString) 2 else 1
            is GoSignatureType -> (u.params.singleOrNull()?.type?.underlying() as? GoSignatureType)?.params?.size ?: 0
            else -> 2
        }
        if (vars > max) {
            val which = when (max) { 0 -> "no iteration variables"; 1 -> "only one iteration variable"; else -> "only two iteration variables" }
            report(x, "range over ${describe(x)} permits $which", "range")
        }
    }

    private fun isIteratorSignature(sig: GoSignatureType): Boolean {
        val yield = sig.params.singleOrNull()?.type?.underlying() as? GoSignatureType ?: return false
        return sig.results.isEmpty() && yield.params.size <= 2 && yield.results.singleOrNull()?.type?.underlying()?.let { isBoolean(it) } == true
    }

    private fun checkTypeSwitchGuard(guard: GoTypeSwitchGuard) {
        guard.varDefinition?.takeIf { it.name == "_" }?.let { report(it, "no new variable on left side of :=", "short-var") }
        val x = guard.expression ?: return
        val t = typer.typeOf(x)
        if (t is GoTypeParamType) { report(x, "cannot use type switch on type parameter value ${exprText(x)}", "type-switch"); return }
        if (!isKnown(t)) return
        if (t.underlying() !is GoInterfaceType) report(x, "${describe(x)} is not an interface", "type-switch")
    }

    private fun checkExprSwitch(stmt: GoExprSwitchStatement) {
        val defaults = stmt.exprCaseClauseList.filter { it.default != null }
        defaults.drop(1).forEach { report(it.default ?: it, "multiple defaults in switch", "duplicate-default") }
        val tag = GoPsiUtil.run { stmt.tag }
        val tt = tag?.let { typer.typeOf(it) } ?: GoBasicType.BOOL
        if (tag != null && tt is GoTupleType) {
            report(tag, if (tt.types.isEmpty()) "${exprText(tag)} (no value) used as value" else "multiple-value ${exprText(tag)} (value of type ${render(tt)}) in single-value context", "multiple-value")
            return
        }
        if (!isKnown(tt) || tt is GoTypeParamType || tt is GoTupleType || (tt as? GoBasicType)?.kind == GoBasicKind.UNTYPED_NIL) return
        val tagType = if (isUntyped(tt)) GoTypePredicates.defaultType(tt) else tt
        if (tag != null && isUntyped(tt)) {
            // The tag takes its default type; a constant that does not fit it invalidates the switch.
            val c = typer.constantOf(tag)
            val f = c?.let { representabilityFailure(it, (tagType as GoBasicType).kind) }
            if (f != null) { report(tag, "cannot use ${describe(tag)} as ${render(tagType)} value in switch expression ($f)", "representability"); return }
        }
        val seen = HashMap<String, GoExpression>()
        for (clause in stmt.exprCaseClauseList) {
            for (e in clause.expressionList) {
                val et = typer.typeOf(e)
                if (!isKnown(et) || et is GoTypeParamType) continue
                if (et is GoTupleType) { report(e, if (et.types.isEmpty()) "${exprText(e)} (no value) used as value" else "multiple-value ${exprText(e)} (value of type ${render(et)}) in single-value context", "multiple-value"); continue }
                if (isUntyped(et)) {
                    val tb = tagType.underlying() as? GoBasicType
                    if (tb != null && !GoTypePredicates.representableKind((et as GoBasicType).kind, tb.kind) || tb == null && tagType.underlying() !is GoInterfaceType && (et as GoBasicType).kind != GoBasicKind.UNTYPED_NIL) {
                        if (tag == null) report(e, "cannot convert ${describe(e)} to type bool", "conversion")
                        else report(e, "invalid case ${exprText(e)} in switch on ${exprText(tag)} (mismatched types ${et} and ${render(tagType)})", "mismatched-types")
                        continue
                    }
                    val c = typer.constantOf(e)
                    if (c != null && tb != null) {
                        val f = representabilityFailure(c, tb.kind)
                        if (f != null) { report(e, if (f == "overflows") "${describe(e)} overflows ${render(tagType)}" else if (f == "truncated") "${describe(e)} truncated to ${render(tagType)}" else "cannot use ${describe(e)} as ${render(tagType)} value in switch case ($f)", "representability"); continue }
                    }
                } else if (!identical(et, tagType) && !assignable(et, tagType) && !assignable(tagType, et)) {
                    report(e, "invalid case ${exprText(e)} in switch on ${tag?.let(::exprText) ?: "true"} (mismatched types ${render(et)} and ${render(tagType)})", "mismatched-types")
                    continue
                }
                // Duplicate constant cases (go/types: by value and type; on an interface tag the constant's default type).
                // Like gc, only integer, floating-point and string values are compared (go/types `goVal`).
                val c = typer.constantOf(e)?.takeIf { it is GoConstant.Int || it is GoConstant.Float || it is GoConstant.Str } ?: continue
                val key = constantKey(c, if (isUntyped(et)) (if (tagType.underlying() is GoInterfaceType) GoTypePredicates.defaultType(et) else tagType) else et)
                val prev = seen.putIfAbsent(key, e)
                if (prev != null) report(e, "duplicate case ${exprText(e)} in expression switch", "duplicate-case")
            }
        }
    }

    /** Identity of a constant case value: the value normalised to its type (so `11./10` and `1.1` collide, `1` and `1.0` on float tags too). */
    private fun constantKey(c: GoConstant, t: GoType): String {
        val k = (t.underlying() as? GoBasicType)?.kind
        val v = when {
            c is GoConstant.Str -> c.render()
            c is GoConstant.Bool -> c.render()
            k != null && (k.isFloat || k.isInteger) -> c.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: c.render()
            else -> c.render()
        }
        return "$v:${render(t)}"
    }

    // --- composite literals ---

    private fun checkCompositeLit(lit: GoCompositeLit) {
        if (!checkCompositeLitTypeArgs(lit)) return
        val value = lit.literalValue ?: return
        val t = typer.typeOf(lit)
        if (!isKnown(t)) return
        checkLiteralValue(value, t)
    }

    /**
     * `T[A, B]{...}`: the parser keeps the type arguments of a composite literal type as direct
     * children of the literal (no TypeArguments node). Checks their count and constraints, and a bare
     * generic type (`T{}`); false when the literal type is invalid.
     */
    private fun checkCompositeLitTypeArgs(lit: GoCompositeLit): Boolean {
        val ref = GoPsiUtil.children(lit, GoTypeReferenceExpression::class.java).firstOrNull() ?: return true
        val spec = resolver.resolveTypeReference(ref) as? GoTypeSpec ?: return true
        if (spec.typeParameters == null || GoUniverse.isBuiltinDeclaration(spec)) return true
        val params = typer.builder.typeParams(spec.typeParameters)
        val hasArgs = lit.node.findChildByType(GoTypes.LBRACK) != null
        if (!hasArgs) {
            report(ref, "cannot use generic type ${genericTypeName(spec)} without instantiation", "generic-no-instantiation")
            return false
        }
        val args = GoPsiUtil.children(lit, PsiType::class.java)
        if (args.size != params.size) { checkTypeArgumentCount(ref, spec.name ?: "", "type", params.size, args.size); return false }
        checkConstraints(params, args.map { typer.builder.typeOf(it) }, args)
        return true
    }

    /** go/types prints an uninstantiated generic type with its type parameter list: `Pair[K comparable, V any]`. */
    private fun genericTypeName(spec: GoTypeSpec): String {
        val params = typer.builder.typeParams(spec.typeParameters)
        if (params.isEmpty()) return spec.name ?: ""
        val parts = ArrayList<String>()
        var i = 0
        while (i < params.size) {
            val b = constraintText(params[i])
            var j = i
            while (j + 1 < params.size && constraintText(params[j + 1]) == b) j++
            parts += params.subList(i, j + 1).joinToString(", ") { it.name } + " " + b
            i = j + 1
        }
        return "${spec.name}[${parts.joinToString(", ")}]"
    }

    /** The constraint of [p] as go/types prints it (`any` for the empty interface). */
    private fun constraintText(p: GoTypeParamType): String = p.bound.let { b -> val u = b.underlying(); if (u is GoInterfaceType && u.allMethods.isEmpty() && u.typeTerms == null && !u.isComparableConstraint && b !is GoNamedType) "any" else render(b) }

    private fun checkLiteralValue(value: GoLiteralValue, t: GoType) {
        val u = (if (t is GoTypeParamType) t.coreType else null) ?: t.underlying()
        val elems = value.elements
        when (u) {
            is GoStructType -> {
                val keyed = elems.count { it.key != null }
                if (keyed != 0 && keyed != elems.size) { report(value, "mixture of field:value and value elements in struct literal", "struct-literal"); return }
                if (keyed == 0) {
                    if (elems.isNotEmpty() && elems.size < u.fields.size) report(value.rbrace ?: value, "too few values in struct literal of type ${render(t)}", "struct-literal")
                    else if (elems.size > u.fields.size) report(elems[u.fields.size], "too many values in struct literal of type ${render(t)}", "struct-literal")
                    for ((i, e) in elems.withIndex()) {
                        val f = u.fields.getOrNull(i) ?: break
                        if (!f.isExported && f.pkgPath != null && f.pkgPath != myPkgPath) { report(e, "implicit assignment to unexported field ${f.name} in struct literal of type ${render(t)}", "struct-literal"); continue }
                        checkElementValue(e.value, f.type, "struct literal")
                    }
                } else {
                    val seen = HashSet<String>()
                    // Go 1.27: keys may name promoted fields. Key -> names of the embedded fields traversed to reach it.
                    val given = LinkedHashMap<String, List<String>>()
                    for (e in elems) {
                        val kx = e.key?.expression ?: continue
                        if (kx !is GoReferenceExpression || kx.qualifier != null) { report(kx, "invalid field name ${exprText(kx)} in struct literal", "struct-literal"); continue }
                        val k = kx
                        val name = k.referenceName ?: continue
                        if (!seen.add(name)) { report(k, "duplicate field name $name in struct literal", "struct-literal"); continue }
                        val f = GoLookup.lookupFieldOrMethod(t, name, packages.packagePathOf(file)) as? GoLookup.Selection.Field ?: continue
                        if (f.path.any { it.type is GoPointerType }) { report(k, "invalid implicit pointer indirection to reach $name", "struct-literal"); continue }
                        val path = f.path.map { it.name }
                        path.firstOrNull { it in given }?.let { report(k, "cannot specify promoted field $name and enclosing embedded field $it", "struct-literal"); continue }
                        given.entries.firstOrNull { name in it.value }?.let { report(k, "cannot specify embedded field $name and enclosed promoted field ${it.key}", "struct-literal"); continue }
                        given[name] = path
                        checkElementValue(e.value, f.member.type, "struct literal")
                    }
                }
            }
            is GoArrayType, is GoSliceType -> {
                val elem = (u as? GoArrayType)?.elem ?: (u as GoSliceType).elem
                val length = (u as? GoArrayType)?.length
                var index = 0L
                val seen = HashSet<Long>()
                for (e in elems) {
                    val key = e.key
                    var validIndex = false
                    if (key != null) {
                        val k = key.expression
                        if (k != null && checkIntegerArgument(k, "int", nonNegative = true)) {
                            val c = typer.constantOf(k)?.toBigInteger()
                            if (c == null) { if (isKnown(typer.typeOf(k))) report(key, "index ${exprText(k)} must be integer constant", "literal-index") }
                            else if (length != null && c.compareTo(BigInteger.valueOf(length)) >= 0) report(key, "index $c out of bounds [0:$length]", "literal-index")
                            else { index = c.toLong(); validIndex = true }
                        }
                    } else if (length != null && index >= length) report(e, "index $index out of bounds [0:$length]", "literal-index")
                    else validIndex = true
                    if (validIndex && !seen.add(index)) report(key ?: e, "duplicate index $index in array or slice literal", "literal-index")
                    index++
                    checkElementValue(e.value, elem, "array or slice literal")
                }
            }
            is GoMapType -> {
                val seenKeys = HashSet<String>()
                for (e in elems) {
                    val key = e.key
                    if (key == null) { report(e, "missing key in map literal", "map-literal"); continue }
                    key.expression?.let { k ->
                        checkAssignable(k, u.key, "map literal")
                        val c = typer.constantOf(k)
                        val kt = typer.typeOf(k)
                        if (c != null && isKnown(kt) && !seenKeys.add(constantKey(c, if (isUntyped(kt)) (if (u.key.underlying() is GoInterfaceType) GoTypePredicates.defaultType(kt) else u.key) else kt))) report(k, "duplicate key ${exprText(k)} in map literal", "map-literal")
                    }
                    key.literalValue?.let { checkLiteralValue(it, (u.key.underlying() as? GoPointerType)?.elem ?: u.key) }
                    checkElementValue(e.value, u.value, "map literal")
                }
            }
            else -> if (u !is GoUnknownType) report(value, "invalid composite literal type ${render(t)}", "composite-literal")
        }
    }

    private fun checkElementValue(v: GoValue?, target: GoType, context: String) {
        v ?: return
        if (!isKnown(target)) return
        v.expression?.let { checkAssignable(it, target, context) }
        v.literalValue?.let { lv ->
            // Struct literal elements never elide their type (`T1{T0: {}}`).
            if (context == "struct literal") { report(lv, "invalid composite literal element type ${render(target)}: missing type", "struct-literal"); return }
            checkLiteralValue(lv, if (target.underlying() is GoPointerType) (target.underlying() as GoPointerType).elem else target)
        }
    }

    // --- assignability ---

    private fun checkAssignable(value: GoExpression, target: GoType, context: String) {
        val vt = typer.typeOf(value)
        if (vt is GoUnknownType || !isKnown(vt) || !isKnown(target)) return
        // A type parameter whose constraint is not resolved may accept anything.
        if (target is GoTypeParamType && !isKnown(target.bound) || vt is GoTypeParamType && !isKnown(vt.bound)) return
        if (isUnresolvedGenericCall(value)) return
        (unparen(value) as? GoBinaryExpr)?.let { b -> if ((opText(b.operator) == "/" || opText(b.operator) == "%") && b.right?.let { typer.constantOf(it)?.toBigDecimal()?.signum() == 0 } == true) return }
        if (vt is GoTupleType) {
            if (vt.types.isEmpty()) report(value, "${value.text} (no value) used as value", "no-value")
            else report(value, "multiple-value ${value.text} (value of type ${render(vt)}) in single-value context", "multiple-value")
            return
        }
        if (typer.isTypeExpression(value)) { report(value, "${value.text} (type) is not an expression", "not-expression"); return }
        // A generic function value is assignable when it can be instantiated to the target.
        if (vt is GoSignatureType && vt.isGeneric) {
            val tu = target.underlying() as? GoSignatureType
            if (tu != null) {
                val u = io.github.golangsupport.semantic.infer.GoUnifier(vt.typeParams.toSet())
                if (u.unify(vt, tu)) return
            } else if (target !is GoTypeParamType && checkNonGeneric(value)) return
        }
        if (vt is GoBasicType && vt.kind == GoBasicKind.UNTYPED_NIL) {
            if (!assignable(vt, target)) report(value, "cannot use nil as ${render(target)} value in $context", "assignability")
            return
        }
        val c = typer.constantOf(value)
        if (c != null && isUntyped(vt) && target is GoTypeParamType) {
            // The constant must be representable by every specific type of the type set (and there must be some).
            val specific = specificTypes(target)
            if (specific == null) { report(value, "cannot use ${describe(value)} as ${render(target)} value in $context", "assignability"); return }
            val bad = specific.firstOrNull { term ->
                val k = (term.underlying() as? GoBasicType)?.kind ?: return@firstOrNull false
                !GoTypePredicates.representableKind((vt as GoBasicType).kind, k) || representabilityFailure(c, k) != null
            }
            if (bad != null) report(value, "cannot use ${describe(value)} as ${render(target)} value in $context: cannot use ${describe(value)} as ${render(bad)} value (in ${target.name})", "representability")
            return
        }
        if (c != null && isUntyped(vt)) {
            val tb = target.underlying() as? GoBasicType
            if (tb != null) {
                if (!GoTypePredicates.representableKind((vt as GoBasicType).kind, tb.kind)) { report(value, "cannot use ${describe(value)} as ${render(target)} value in $context", "assignability"); return }
                val f = representabilityFailure(c, tb.kind)
                if (f != null) report(value, "cannot use ${describe(value)} as ${render(target)} value in $context${if (f == "not representable") "" else " ($f)"}", "representability")
                return
            }
        }
        if (c == null && isUntyped(vt) && target.underlying() is GoBasicType && (vt as GoBasicType).kind != GoBasicKind.UNTYPED_NIL) {
            // A non-constant untyped expression (`1<<s + 1.2`): its constant leaves must fit the target.
            if (!GoTypePredicates.representableKind(vt.kind, (target.underlying() as GoBasicType).kind) && !(vt.kind == GoBasicKind.UNTYPED_BOOL)) { report(value, "cannot use ${describe(value)} as ${render(target)} value in $context", "assignability"); return }
            checkUntypedLeaves(value, target)
            return
        }
        if (assignable(vt, target)) return
        val tpDetail = when {
            // go/types names the failing specific type only when the other side is not a named type.
            target is GoTypeParamType && !GoTypePredicates.isNamed(vt) -> target.terms?.firstOrNull { !assignable(vt, it.type) }?.let { ": cannot assign ${render(vt)} to ${render(it.type)} (in ${render(target)})" }
            vt is GoTypeParamType && !GoTypePredicates.isNamed(target) -> vt.terms?.firstOrNull { !assignable(it.type, target) }?.let { ": cannot assign ${render(it.type)} (in ${render(vt)}) to ${render(target)}" }
            else -> null
        }
        val detail = tpDetail ?: (target.underlying() as? GoInterfaceType)?.takeIf { target !is GoTypeParamType }?.let { ": ${render(vt)} does not implement ${render(target)} ${implementsDetail(vt, it)}" } ?: ""
        report(value, "cannot use ${describe(value)} as ${render(target)} value in $context$detail", "assignability")
    }

    private fun implementsDetail(type: GoType, iface: GoInterfaceType): String {
        val missing = GoTypePredicates.missingMethods(type, iface)
        val m = missing.firstOrNull() ?: return "(${render(type)} missing in type set)"
        val set = GoLookup.methodSet(type)
        val wrong = set.firstOrNull { it.name == m.name }
        if (wrong != null) return "(wrong type for method ${m.name})\n\t\thave ${m.name}${render(wrong.signature).removePrefix("func")}\n\t\twant ${m.name}${render(m.signature).removePrefix("func")}"
        if (type !is GoPointerType && GoLookup.methodSet(GoPointerType(type)).any { it.name == m.name }) return "(method ${m.name} has pointer receiver)"
        return "(missing method ${m.name})"
    }

    /** A call whose type arguments could not all be inferred: its type still mentions the callee's parameters. */
    private fun isUnresolvedGenericCall(e: GoExpression): Boolean {
        val call = unparen(e) as? GoCallExpr ?: return false
        return typer.calleeSignature(call)?.isGeneric == true
    }

    /** [sole]: the only value of its statement, whose tuple results `checkArity` already reports. */
    private fun checkValueUsable(value: GoExpression, sole: Boolean) {
        val vt = typer.typeOf(value)
        if (vt is GoTupleType && sole) return
        if (vt is GoTupleType && vt.types.isEmpty()) report(value, "${value.text} (no value) used as value", "no-value")
        else if (typer.isTypeExpression(value) && isKnown(vt)) report(value, "${value.text} (type) is not an expression", "not-expression")
    }

    /** go/types representability: null when [c] fits [kind], else "overflows" / "truncated" / "not representable". */
    private fun representabilityFailure(c: GoConstant, kind: GoBasicKind): String? {
        // An untyped integer beyond the constant precision is already reported as an overflow of its operation.
        if (c is GoConstant.Int && c.value.abs().bitLength() > UNTYPED_INT_PRECISION) return null
        when {
            kind.isBoolean -> return if (c is GoConstant.Bool) null else "not representable"
            kind.isString -> return if (c is GoConstant.Str) null else "not representable"
            kind.isInteger -> {
                if (c is GoConstant.Bool || c is GoConstant.Str) return "not representable"
                val i = c.toBigInteger() ?: return "truncated"
                val (min, max) = integerRange(kind) ?: return null
                return if (i < min || i > max) "overflows" else null
            }
            kind.isFloat -> {
                val d = c.toBigDecimal() ?: return if (c is GoConstant.Complex) "overflows" else "not representable"
                val max = floatMax(kind)
                return if (d.abs() > max) "overflows" else null
            }
            kind.isComplex -> {
                if (c is GoConstant.Bool || c is GoConstant.Str) return "not representable"
                val max = floatMax(if (kind == GoBasicKind.COMPLEX64) GoBasicKind.FLOAT32 else GoBasicKind.FLOAT64)
                val (re, im) = when (c) { is GoConstant.Complex -> c.re to c.im; else -> (c.toBigDecimal() ?: return null) to BigDecimal.ZERO }
                return if (re.abs() > max || im.abs() > max) "overflows" else null
            }
            else -> return null
        }
    }

    private fun floatMax(kind: GoBasicKind): BigDecimal =
        if (kind == GoBasicKind.FLOAT32) BigDecimal("3.40282346638528859811704183484516925440e+38") else BigDecimal("1.797693134862315708145274237317043567981e+308")

    private fun integerRange(kind: GoBasicKind): Pair<BigInteger, BigInteger>? = when (kind) {
        GoBasicKind.INT8 -> BigInteger.valueOf(-128) to BigInteger.valueOf(127)
        GoBasicKind.INT16 -> BigInteger.valueOf(-32768) to BigInteger.valueOf(32767)
        GoBasicKind.INT32, GoBasicKind.UNTYPED_RUNE -> BigInteger.valueOf(Int.MIN_VALUE.toLong()) to BigInteger.valueOf(Int.MAX_VALUE.toLong())
        GoBasicKind.INT, GoBasicKind.INT64 -> BigInteger.valueOf(Long.MIN_VALUE) to BigInteger.valueOf(Long.MAX_VALUE)
        GoBasicKind.UINT8 -> BigInteger.ZERO to BigInteger.valueOf(255)
        GoBasicKind.UINT16 -> BigInteger.ZERO to BigInteger.valueOf(65535)
        GoBasicKind.UINT32 -> BigInteger.ZERO to BigInteger.valueOf(4294967295L)
        GoBasicKind.UINT, GoBasicKind.UINT64, GoBasicKind.UINTPTR -> BigInteger.ZERO to BigInteger("18446744073709551615")
        GoBasicKind.UNTYPED_INT -> null
        else -> null
    }

    // --- operand descriptions (go/types operand.String) ---

    /** Port of go/types `lookupError`: hints about misspelled or unexported members. */
    private fun lookupError(type: GoType, sel: String, structLit: Boolean): String {
        val alt = GoLookup.alternativeMember(if (type is GoTypeParamType) type.coreType ?: type else type, sel, fieldsOnly = structLit)
        val myPkg = packages.packagePathOf(file)
        var kind = "missing"
        if (alt != null) {
            val same = alt.pkgPath == null || alt.pkgPath == myPkg
            val selExported = Character.isUpperCase(sel.codePointAt(0))
            val altExported = Character.isUpperCase(alt.name.codePointAt(0))
            fun tail(s: String) = if (s.length > 1) s.substring(s.offsetByCodePoints(0, 1)) else ""
            kind = when {
                same -> if (alt.name != sel) "misspelled" else "missing"
                selExported -> if (altExported) "misspelled" else if (tail(sel) == tail(alt.name)) "unexported" else "missing"
                altExported -> if (tail(sel) == tail(alt.name)) "misspelled" else "missing"
                sel == alt.name -> "inaccessible"
                else -> "missing"
            }
        }
        val typ = render(type)
        if (structLit) return when (kind) {
            "misspelled" -> "unknown field $sel in struct literal of type $typ, but does have ${alt!!.name}"
            "unexported" -> "unknown field $sel in struct literal of type $typ, but does have unexported ${alt!!.name}"
            "inaccessible" -> "cannot refer to unexported field ${alt!!.name} in struct literal of type $typ"
            else -> "unknown field $sel in struct literal of type $typ"
        }
        return when (kind) {
            "misspelled" -> "type $typ has no field or method $sel, but does have ${alt!!.kind} ${alt.name}"
            "unexported" -> "type $typ has no field or method $sel, but does have unexported ${alt!!.kind} ${alt.name}"
            "inaccessible" -> "cannot refer to unexported ${alt!!.kind} ${alt.name}"
            else -> "type $typ has no field or method $sel"
        }
    }

    /**
     * go/types `ExprString`: the expression re-printed from its tokens (no comments, canonical
     * spacing) with function and composite literal bodies elided as `{...}`.
     */
    private fun exprText(e: PsiElement): String {
        val sb = StringBuilder()
        var prevWord = false
        var leaf: PsiElement? = PsiTreeUtil.getDeepestFirst(e)
        val end = e.textRange.endOffset
        while (leaf != null && leaf.textRange.startOffset < end) {
            val type = leaf.node.elementType
            if (type === com.intellij.psi.TokenType.WHITE_SPACE || io.github.golangsupport.lang.psi.GoTokenSets.COMMENTS.contains(type)) { leaf = PsiTreeUtil.nextLeaf(leaf); continue }
            val parent = leaf.parent
            if (type === GoTypes.LBRACE && (parent is GoLiteralValue && parent.elements.isNotEmpty() || parent is GoBlock && parent.parent is GoFunctionLit) && parent !== e) {
                // Elide the body: jump to the token after the closing brace.
                sb.append(if (parent is GoBlock) " {...}" else "{...}")
                leaf = PsiTreeUtil.nextLeaf(PsiTreeUtil.getDeepestLast(parent))
                prevWord = false
                continue
            }
            val text = leaf.text
            val word = type === GoTypes.IDENTIFIER || io.github.golangsupport.lang.psi.GoTokenSets.KEYWORDS.contains(type) || io.github.golangsupport.lang.psi.GoTokenSets.LITERALS.contains(type)
            when {
                (parent is GoBinaryExpr || parent is io.github.golangsupport.lang.psi.GoAssignOp) && leaf !is GoExpression && type !== GoTypes.LPAREN && type !== GoTypes.RPAREN -> { sb.append(' ').append(text).append(' '); prevWord = false }
                type === GoTypes.COMMA -> { sb.append(", "); prevWord = false }
                type === GoTypes.COLON && parent is GoElement -> { sb.append(": "); prevWord = false }
                else -> {
                    if (word && prevWord) sb.append(' ')
                    sb.append(text)
                    prevWord = word
                    if (type === GoTypes.RPAREN && parent is io.github.golangsupport.lang.psi.GoParameters && (parent.parent as? io.github.golangsupport.lang.psi.GoSignature)?.result != null) { sb.append(' '); prevWord = false }
                }
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return sb.toString()
    }

    private fun isNilLiteral(e: GoExpression): Boolean {
        val x = unparen(e)
        if (x !is GoReferenceExpression || x.qualifier != null || x.referenceName != "nil") return false
        val r = resolver.resolveReferenceExpression(x)
        return r.isEmpty() || r.any { it.element is GoNamedElement && GoUniverse.isBuiltinDeclaration(it.element as GoNamedElement) }
    }

    private fun describe(expr: GoExpression): String {
        val text = exprText(expr)
        val x = unparen(expr)
        if (isNilLiteral(expr)) return "nil"
        val t = typer.typeOf(expr)
        if (t is GoBasicType && t.kind == GoBasicKind.UNTYPED_NIL) return "nil"
        if (typer.isTypeExpression(expr)) return "$text (type)"
        if (x is GoReferenceExpression && x.qualifier == null && resolver.resolveReferenceExpression(x).isEmpty() && (x.referenceName ?: "") in GoUniverse.FUNCTIONS) return "$text (built-in)"
        val c = typer.constantOf(expr)
        if (c != null) {
            val v = c.toString()
            return if (isUntyped(t)) "$text (${t} constant${if (v == text) "" else " $v"})" else "$text (constant $v of type ${render(t)})"
        }
        if (t is GoTupleType) return if (t.types.isEmpty()) "$text (no value)" else "$text (value of type ${render(t)})"
        if (isUntyped(t)) return "$text (${t} value)"
        val kind = when {
            x is GoIndexOrSliceExpr && !x.isSlice && typer.typeOf(x.expression ?: x).let { xt -> (if (xt is GoTypeParamType) xt.coreType else null) ?: xt.underlying() } is GoMapType -> "map index expression"
            x is GoUnaryExpr && x.operator === GoTypes.ARROW -> "comma, ok expression"
            isVariable(x) -> "variable"
            else -> "value"
        }
        return "$text ($kind of ${typeDescription(t)})"
    }

    /** go/types: `type T`, `struct type T`, `int type MyInt`, `type P constrained by any`, `generic type func[T any]()`. */
    private fun typeDescription(t: GoType): String {
        val sb = StringBuilder()
        if (t is GoSignatureType && t.isGeneric || t is GoNamedType && t.isGeneric) sb.append("generic ")
        if (t is GoNamedType) {
            val what = when (val u = t.underlying()) {
                is GoBasicType -> u.name
                is GoArrayType -> "array"; is GoSliceType -> "slice"; is GoStructType -> "struct"; is GoPointerType -> "pointer"
                is GoSignatureType -> "func"; is GoInterfaceType -> "interface"; is GoMapType -> "map"; is GoChanType -> "chan"
                else -> ""
            }
            if (what.isNotEmpty()) sb.append(what).append(' ')
        }
        sb.append("type ").append(render(t))
        if (t is GoTypeParamType) sb.append(" constrained by ").append(constraintText(t))
        return sb.toString()
    }

    private fun isVariable(x: GoExpression): Boolean = when (x) {
        is GoReferenceExpression -> {
            val r = resolver.resolveReferenceExpression(x).firstOrNull()
            val d = r?.element
            d is GoVarDefinition || d is GoParamDefinition || d is GoReceiver || (r is GoResolver.Result.Selection && r.selection is GoLookup.Selection.Field) || (r is GoResolver.Result.Member && d is GoVarDefinition)
        }
        is GoIndexOrSliceExpr -> !x.isSlice && typer.typeOf(x.expression ?: x).underlying().let { it is GoSliceType || it is GoArrayType || it is GoPointerType }
        is GoUnaryExpr -> x.operator === GoTypes.MUL
        is GoParenthesesExpr -> (x.inner as? GoExpression)?.let(::isVariable) ?: false
        else -> false
    }

    private fun unparen(e: GoExpression): GoExpression {
        var x = e
        while (x is GoParenthesesExpr) x = x.inner as? GoExpression ?: return x
        return x
    }

    private fun opText(op: com.intellij.psi.tree.IElementType?): String? = when (op) {
        GoTypes.ADD -> "+"; GoTypes.SUB -> "-"; GoTypes.MUL -> "*"; GoTypes.QUO -> "/"; GoTypes.REM -> "%"
        GoTypes.AND -> "&"; GoTypes.OR -> "|"; GoTypes.XOR -> "^"; GoTypes.AND_NOT -> "&^"; GoTypes.SHL -> "<<"; GoTypes.SHR -> ">>"
        GoTypes.LAND -> "&&"; GoTypes.LOR -> "||"; GoTypes.EQL -> "=="; GoTypes.NEQ -> "!="; GoTypes.LSS -> "<"
        GoTypes.LEQ -> "<="; GoTypes.GTR -> ">"; GoTypes.GEQ -> ">="
        else -> null
    }

    // --- scopes: redeclarations, unused, labels, break/continue ---

    private fun checkRedeclarations(block: GoBlock) {
        val seen = HashMap<String, PsiElement>()
        val owner = block.parent
        fun add(e: GoNamedElement) {
            val n = e.name ?: return
            if (n == "_") return
            val prev = seen.putIfAbsent(n, e)
            if (prev != null) report(e.nameIdentifier ?: e, "$n redeclared in this block", "redeclared")
        }
        fun addTypeParams(tp: io.github.golangsupport.lang.psi.GoTypeParameters?) { tp?.let { GoScopes.typeParamDefinitions(it).forEach(::add) } }
        when (owner) {
            is GoFunctionDeclaration -> { addTypeParams(owner.typeParameters); owner.signature?.let { sig -> sig.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(::add) }; sig.result?.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(::add) } } }
            is GoMethodDeclaration -> { owner.receiver?.let { if (it.name != null) add(it) }; owner.signature?.let { sig -> sig.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(::add) }; sig.result?.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(::add) } } }
            is GoFunctionLit -> owner.signature?.let { sig -> sig.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(::add) }; sig.result?.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(::add) } }
        }
        for (s0 in block.statementList) {
            val s = (s0 as? GoLabeledStatement)?.statement ?: s0
            if (s is GoShortVarDeclaration) {
                // `a, b := ...` may redeclare names from the same block as long as one is new.
                val defs = s.varDefinitionList
                val fresh = defs.filter { it.name != "_" && it.name !in seen }
                if (fresh.isEmpty() && defs.any { it.name != "_" }) continue
                fresh.forEach { seen.putIfAbsent(it.name!!, it) }
                continue
            }
            GoPsiUtil.declarationsOf(s).forEach(::add)
        }
    }

    private fun checkPackageRedeclarations(f: GoFile) {
        val scope = packages.scopeOf(f)
        val names = HashSet<String>()
        val mine = (f.functions + f.types + f.vars + f.consts).filter { it.containingFile == f }
        for (d in mine) {
            val n = d.name ?: continue
            if ((n == "main" && f.packageName == "main" || n == "init") && d !is GoFunctionDeclaration) { report(d.nameIdentifier ?: d, "cannot declare $n - must be func", "redeclared"); continue }
            if (n == "_" || n == "init" || n == "main" && f.packageName == "main" || !names.add(n)) continue
            val all = scope.lookup(n)
            if (all.size <= 1) continue
            val first = all.minWith(compareBy({ it.containingFile.name }, { it.textOffset }))
            for (e in all) if (e !== first && e.containingFile == f) report(e.nameIdentifier ?: e, "$n redeclared in this block", "redeclared")
        }
        // Methods: same receiver type and name twice.
        val methods = f.methods.groupBy { "${it.receiverTypeName}.${it.name}" }
        for ((_, ms) in methods) if (ms.size > 1) ms.drop(1).forEach { report(it.nameIdentifier ?: it, "method ${it.receiverTypeName}.${it.name} already declared", "redeclared") }
    }

    private fun checkBreak(stmt: GoBreakStatement) {
        val label = stmt.labelRef
        if (label != null && label.text == "_") { report(label, "invalid break label _", "label"); return }
        if (label != null) {
            val target = GoScopes.resolveLabel(stmt, label.identifier?.text ?: "")
            if (target != null && !enclosesAsLabeled(stmt, target) { it is GoForStatement || it is GoExprSwitchStatement || it is GoTypeSwitchStatement || it is GoSelectStatement }) report(label, "invalid break label ${label.text}", "label")
            return
        }
        if (!hasEnclosing(stmt) { it is GoForStatement || it is GoExprSwitchStatement || it is GoTypeSwitchStatement || it is GoSelectStatement }) report(stmt, "break is not in a loop, switch, or select", "break-continue")
    }

    private fun checkContinue(stmt: GoContinueStatement) {
        val label = stmt.labelRef
        if (label != null && label.text == "_") { report(label, "invalid continue label _", "label"); return }
        if (label != null) {
            val target = GoScopes.resolveLabel(stmt, label.identifier?.text ?: "")
            if (target != null && !enclosesAsLabeled(stmt, target) { it is GoForStatement }) report(label, "invalid continue label ${label.text}", "label")
            return
        }
        if (!hasEnclosing(stmt) { it is GoForStatement }) report(stmt, "continue is not in a loop", "break-continue")
    }

    private fun hasEnclosing(e: PsiElement, pred: (PsiElement) -> Boolean): Boolean {
        var p: PsiElement? = e.parent
        while (p != null && p !is GoFile) {
            if (p is GoFunctionOrMethodDeclaration || p is GoFunctionLit) return false
            if (pred(p)) return true
            p = p.parent
        }
        return false
    }

    private fun enclosesAsLabeled(e: PsiElement, label: GoLabelDefinition, pred: (PsiElement) -> Boolean): Boolean {
        val labeled = label.parent as? GoLabeledStatement ?: return false
        val target = labeled.statement ?: return false
        if (!pred(target)) return false
        var p: PsiElement? = e.parent
        while (p != null && p !is GoFile) {
            if (p === target) return true
            if (p is GoFunctionOrMethodDeclaration || p is GoFunctionLit) return false
            p = p.parent
        }
        return false
    }

    /** Unused local variables under [root] (the file or one outermost body). */
    private fun checkUnusedLocals(root: PsiElement) {
        for (def in PsiTreeUtil.findChildrenOfType(root, GoVarDefinition::class.java)) {
            val name = def.name ?: continue
            if (name == "_" || def in usedLocals) continue
            if (!GoPsiUtil.isInsideFunctionBody(def)) continue
            val parent = def.parent
            if (parent is GoShortVarDeclaration && isRedeclaredInSameScope(def)) continue // an assignment to an existing variable
            if (parent is GoTypeSwitchGuard) {
                // Used in at least one clause (references resolve to the definition).
                report(def, "$name declared and not used", "unused-variable")
                continue
            }
            report(def, "declared and not used: $name", "unused-variable")
        }
    }

    /** Duplicate and unused labels under [root] (the file or one outermost body), per function: literals have their own labels. */
    private fun checkLabels(root: PsiElement) {
        val labelsByOwner = PsiTreeUtil.findChildrenOfType(root, GoLabelDefinition::class.java).groupBy { GoPsiUtil.functionOwner(it) }
        for ((_, labels) in labelsByOwner) {
            val seen = HashMap<String, GoLabelDefinition>()
            for (label in labels) {
                val n = label.name ?: continue
                if (n == "_") continue
                val prev = seen.putIfAbsent(n, label)
                if (prev != null) { report(label, "label $n already declared", "redeclared"); continue }
                if (label !in usedLabels) report(label, "label $n declared and not used", "unused-label")
            }
        }
    }
}
