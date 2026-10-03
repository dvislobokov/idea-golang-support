package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoIntentionText
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoContinueStatement
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFallthroughStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGotoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeParameterDeclaration
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/** Why a selection cannot be extracted; the message goes to the error hint. */
internal class GoExtractRefusal(message: String) : Exception(message)

/**
 * What Extract Function / Method writes: the call replacing [replace], the new declaration inserted at [insertAt] (after the
 * enclosing top-level declaration), the imports its types need, and the offset of the new name inside [declaration].
 */
internal class GoExtractPlan(
    val replace: TextRange,
    val call: String,
    val insertAt: Int,
    val declaration: String,
    val imports: Collection<String>,
    val name: String,
    val isMethod: Boolean,
)

/**
 * The analysis of Extract Function: a selection of whole statements of one block (or case clause), or one expression.
 *
 * Parameters are the variables of the enclosing functions declared outside the selection and used in it, in order of first use,
 * unless the selection assigns them unconditionally before any read (then the new function declares them itself). Results are
 * the variables the selection writes whose value is read after it (the def-use chains of [GoControlFlow]: a read reached from a
 * write inside without crossing another write and standing outside the selection; inside a loop every written variable the loop
 * reads counts, since the next iteration reads it), and the variables declared at the top of the selection and used after it
 * (`a, b := extracted(x)`). A variable both passed in and written goes in and comes back: Go has no out-parameters and pointers
 * would change the code more than the user asked. Escaping variables (address taken, captured) are taken as written.
 *
 * A selection whose every path ends in `return` (or that is terminating in a function with results) becomes `return
 * extracted(…)`; a `return` on some paths only, a `break` / `continue` / `goto` / `fallthrough` leaving the selection, and a
 * `defer` (it would run at the end of the new function) are refused. When the selection uses the receiver of the enclosing method
 * the result is a method on the same receiver. Type parameters of the enclosing function that the selection or the parameter
 * types use are copied with their constraints (and the type parameters those constraints name).
 */
internal object GoExtractFunction {

    const val DEFAULT_NAME = "extracted"

    /** The plan for the selection [start, end) of [file], with [name] (or a free variant of `extracted`); throws [GoExtractRefusal]. */
    fun plan(file: GoFile, start: Int, end: Int, name: String?): GoExtractPlan {
        val text = file.viewProvider.contents
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) refuse("Select statements or an expression inside a function body")
        val expr = GoExtraction.selectedExpression(file, s, e)
        val statements = statementsIn(file, s, e)
        return when {
            statements != null && (expr == null || statements.size == 1 && statements[0].textRange == expr.textRange) -> Analysis(file, statements, null).plan(name)
            expr != null -> Analysis(file, emptyList(), expr).plan(name)
            else -> refuse("Selection should cover whole statements of one block or a single expression")
        }
    }

    /** The whole statements of one block or case clause covered by [s, e) (blanks and comments around allowed), or null. */
    private fun statementsIn(file: GoFile, s: Int, e: Int): List<GoStatement>? {
        val first = file.findElementAt(s) ?: return null
        val last = file.findElementAt(e - 1) ?: return null
        var c: PsiElement? = PsiTreeUtil.findCommonParent(first, last)
        while (c != null && c !is PsiFile && c !is GoFunctionOrMethodDeclaration) {
            if (isContainer(c)) {
                val inner = statementsOf(c).filter { it.textRange.intersects(TextRange(s, e)) && it.textRange.endOffset > s && it.textRange.startOffset < e }
                if (inner.isNotEmpty() && inner.all { it.textRange.startOffset >= s && it.textRange.endOffset <= e } &&
                    onlyBlanks(file, s, inner.first().textRange.startOffset) && onlyBlanks(file, inner.last().textRange.endOffset, e)) return inner
                if (inner.isNotEmpty()) return null
            }
            c = c.parent
        }
        return null
    }

    private fun isContainer(e: PsiElement) = e is GoBlock || e is GoExprCaseClause || e is GoTypeCaseClause || e is GoCommClause

    private fun statementsOf(container: PsiElement): List<GoStatement> = container.children.filterIsInstance<GoStatement>()

    private fun onlyBlanks(file: GoFile, from: Int, to: Int): Boolean {
        var o = from
        while (o < to) {
            val leaf = file.findElementAt(o) ?: return false
            if (leaf !is PsiWhiteSpace && leaf !is PsiComment && leaf.node.elementType != GoTypes.SEMICOLON) return false
            o = leaf.textRange.endOffset
        }
        return true
    }

    fun refuse(message: String): Nothing = throw GoExtractRefusal(message)

    /** One variable the selection uses: [read] it before writing, [written] in it, [usedAfter] its value later, [declaredInside]. */
    private class Use(val variable: GoNamedElement, val firstUse: Int) {
        var input = false
        var written = false
        var output = false
        var declaredInside = false
        var definedByRedeclaration = false
    }

    private class Analysis(val file: GoFile, val statements: List<GoStatement>, val expr: GoExpression?) {
        val service = GoSemanticService.getInstance(file.project)
        val source = GoSourceText(file)
        val elements: List<PsiElement> = if (expr != null) listOf(expr) else statements
        val range = TextRange(elements.first().textRange.startOffset, elements.last().textRange.endOffset)
        val anchor: PsiElement = elements.first()
        val owner: PsiElement = GoPsiUtil.functionOwner(anchor) ?: refuse("Select statements or an expression inside a function body")
        val top: GoFunctionOrMethodDeclaration = PsiTreeUtil.getTopmostParentOfType(anchor, GoFunctionOrMethodDeclaration::class.java)
            ?: refuse("Select statements or an expression inside a function body")
        val flow: GoControlFlow? = GoControlFlow.enclosing(anchor)

        fun inside(e: PsiElement) = range.contains(e.textRange)

        fun plan(name: String?): GoExtractPlan {
            if (expr == null && flow == null) refuse("The function is too complex to analyze (syntax errors or unsupported constructs)")
            expr?.let { x -> GoExtraction.rejectReason(x)?.let { refuse(it) } }
            checkJumps()
            val returnMode = expr == null && returnMode()
            val uses = collectUses()
            val receiver = (top as? GoMethodDeclaration)?.receiver?.takeIf { r -> uses.any { it.variable == r } }
            if (receiver != null && uses.first { it.variable == receiver }.written) refuse("The selection assigns the receiver '${receiver.name}'")
            if (expr != null) uses.firstOrNull { it.written }?.let { refuse("The expression changes '${it.variable.name}' declared outside it") }
            val params = uses.filter { it.input && it.variable != receiver }
            val outputs = if (returnMode || expr != null) emptyList() else uses.filter { it.output }.sortedBy { it.firstUse }
            val locals = uses.filter { !it.input && !it.definedByRedeclaration && !it.declaredInside && it.variable != receiver }

            val paramTypes = params.map { typeOf(it.variable) }
            val resultTypes: List<GoType> = when {
                expr != null -> expressionResults(expr)
                returnMode -> service.enclosingResultTypes(anchor) ?: emptyList()
                else -> outputs.map { typeOf(it.variable) }
            }
            val typeParams = typeParameters(paramTypes + resultTypes + locals.map { typeOf(it.variable) }, receiver != null)

            val funcName = name ?: freeName(receiver)
            val signature = buildString {
                append("func ")
                if (receiver != null) append((top as GoMethodDeclaration).receiver!!.text).append(' ')
                append(funcName)
                if (typeParams.isNotEmpty()) append('[').append(typeParams.joinToString(", ")).append(']')
                append('(').append(params.indices.joinToString(", ") { "${params[it].variable.name} ${source.type(paramTypes[it])}" }).append(')')
                val results = resultTypes.map { source.type(it) }
                if (results.size == 1) append(' ').append(results[0]) else if (results.size > 1) append(" (").append(results.joinToString(", ")).append(')')
            }
            val body = StringBuilder()
            for (l in locals) body.append("\tvar ${l.variable.name} ${source.type(typeOf(l.variable))}\n")
            if (expr != null) body.append("\treturn ").append(reindent(expr)).append('\n')
            else {
                body.append(reindent(null)).append('\n')
                if (outputs.isNotEmpty()) body.append("\treturn ").append(outputs.joinToString(", ") { it.variable.name!! }).append('\n')
            }
            val declaration = "\n\n$signature {\n$body}"

            val args = params.joinToString(", ") { it.variable.name!! }
            val invocation = (receiver?.let { "${it.name}." } ?: "") + "$funcName($args)"
            val indent = GoIntentionText.indentAt(file.viewProvider.contents, range.startOffset)
            val call = when {
                expr != null -> invocation
                returnMode && resultTypes.isNotEmpty() -> "return $invocation"
                returnMode -> if (endsBody()) invocation else "$invocation\n${indent}return"
                outputs.isEmpty() -> invocation
                else -> callWithResults(outputs, invocation, indent)
            }
            return GoExtractPlan(range, call, top.textRange.endOffset, declaration, source.imports, funcName, receiver != null)
        }

        // --- variables ---

        private fun typeOf(v: GoNamedElement): GoType {
            val t = GoTypePredicates.defaultType(service.declarationType(v))
            if (mentionsUnknown(t)) refuse("Cannot infer the type of '${v.name}'")
            return t
        }

        private fun expressionResults(x: GoExpression): List<GoType> {
            var t = service.typeOf(x)
            if (GoExtraction.isValueless(t)) refuse("The expression has no value")
            if (GoTypePredicates.isUntyped(t)) t = service.expectedTypeAt(x)?.takeIf { !GoTypePredicates.isUntyped(it) } ?: GoTypePredicates.defaultType(t)
            val list = if (t is GoTupleType) t.types else listOf(t)
            if (list.any(::mentionsUnknown)) refuse("Cannot infer the type of the expression")
            return list
        }

        private fun mentionsUnknown(t: GoType): Boolean { var unknown = false; walk(t) { if (it is GoUnknownType) unknown = true }; return unknown }

        /** A variable of a function around the selection (not package-level), declared outside the selection. */
        private fun isOuterLocal(v: PsiElement): Boolean =
            (v is GoVarDefinition || v is GoParamDefinition || v is GoReceiver) && v.containingFile == file && !inside(v) && top.textRange.contains(v.textRange)

        private fun collectUses(): List<Use> {
            val uses = LinkedHashMap<GoNamedElement, Use>()
            val refs = elements.flatMap { PsiTreeUtil.findChildrenOfType(it, GoReferenceExpression::class.java) + listOfNotNull(it as? GoReferenceExpression) }
                .filter { it.expression == null }.sortedBy { it.textRange.startOffset }
            val refsByVar = LinkedHashMap<GoNamedElement, MutableList<GoReferenceExpression>>()
            for (ref in refs) {
                val target = service.resolve(ref).firstOrNull() ?: continue
                if (target.containingFile == file && !inside(target) && top.textRange.contains(target.textRange)) {
                    if (target is GoConstDefinition) refuse("The selection uses the local constant '${(target as GoNamedElement).name}'")
                }
                if (!isOuterLocal(target)) continue
                val v = target as GoNamedElement
                uses.getOrPut(v) { Use(v, ref.textRange.startOffset) }
                refsByVar.getOrPut(v) { ArrayList() } += ref
            }
            checkLocalTypes()
            // `a, err := f()` redeclaring an outer `err`: a definition access of the flow, no reference
            val redeclared = flow?.accesses.orEmpty().filter { it.kind == GoFlowAccess.Kind.DEFINE && inside(it.element) && isOuterLocal(it.variable) }
            for (a in redeclared) uses.getOrPut(a.variable) { Use(a.variable, a.element.textRange.startOffset) }
            val sorted = uses.values.sortedBy { it.firstUse }

            for (use in sorted) {
                val v = use.variable
                val vRefs = refsByVar[v].orEmpty()
                val flowWrites = flow?.accesses.orEmpty().filter { it.variable == v && it.isWrite && inside(it.element) }
                // an escaping variable may change through a method with a pointer receiver
                val escaping = flow?.let { it.indexOf(v) >= 0 && it.isEscaping(v) } == true && vRefs.any(::isMethodReceiver)
                if (vRefs.any { isWrite(it) && insideLiteral(it) }) refuse("A function literal in the selection assigns '${v.name}' declared outside it")
                use.written = flowWrites.isNotEmpty() || vRefs.any(::isWrite) || escaping
                val first = (vRefs.map { it as PsiElement } + flowWrites.filter { it.kind == GoFlowAccess.Kind.DEFINE }.map { it.element }).minByOrNull { it.textRange.startOffset }
                use.definedByRedeclaration = first != null && first !is GoReferenceExpression && unconditionalDefinition(first, v)
                use.input = !use.definedByRedeclaration && !(first is GoReferenceExpression && unconditionalAssignment(first, v))
                if (use.written && expr == null) use.output = usedAfter(v, flowWrites, escaping, use.input)
            }
            val result = sorted.toMutableList()
            if (expr == null) result += declaredInsideUsedAfter()
            return result
        }

        /** Local types used in the selection but declared outside it cannot be named by a package-level function. */
        private fun checkLocalTypes() {
            for (e in elements) for (tr in PsiTreeUtil.findChildrenOfType(e, GoTypeReferenceExpression::class.java)) {
                val t = service.resolve(tr) ?: continue
                if (t is GoTypeSpec && t.containingFile == file && !inside(t) && GoPsiUtil.functionOwner(t) != null) refuse("The selection uses the local type '${t.name}'")
            }
        }

        private fun isMethodReceiver(ref: GoReferenceExpression): Boolean {
            val selector = ref.parent as? GoReferenceExpression ?: return false
            return selector.expression === ref && (selector.parent as? GoCallExpr)?.expression === selector
        }

        private fun insideLiteral(e: PsiElement): Boolean {
            val lit = PsiTreeUtil.getParentOfType(e, GoFunctionLit::class.java) ?: return false
            return inside(lit)
        }

        /** `x = …`, `x.f = …`, `x[i] = …`, `x++`, `x += …`, `for x = range`, `x = <-ch`, `&x`, `&x.f`: the variable may change. */
        private fun isWrite(ref: GoReferenceExpression): Boolean {
            var child: PsiElement = ref
            var p: PsiElement? = ref.parent
            while (p is GoReferenceExpression && p.expression === child || p is GoIndexOrSliceExpr && p.expression === child || p is GoParenthesesExpr) {
                // `p.f = …` through a pointer, `s[i] = …` of a slice or map: the variable itself keeps its value
                if (p !is GoParenthesesExpr && child is GoExpression && sharesStorage(service.typeOf(child))) return false
                child = p!!
                p = p.parent
            }
            if (p is GoUnaryExpr && p.and != null) return true
            if (p !is GoLeftHandExprList) return false
            val q = p.parent.let { if (it is GoSimpleStatement) it.statement ?: it else it }
            return q is GoAssignmentStatement || q is GoIncDecStatement || q is GoRangeClause || q is GoRecvStatement || p.parent is GoAssignmentStatement
        }

        private fun sharesStorage(t: GoType): Boolean = t.underlying().let { it is GoPointerType || it is GoSliceType || it is GoMapType }

        /** [ref] is `x` of `x = …` (plain `=`) in a statement of the selection's own block that does not read [v] before. */
        private fun unconditionalAssignment(ref: GoReferenceExpression, v: GoNamedElement): Boolean {
            val list = ref.parent as? GoLeftHandExprList ?: return false
            val assignment = list.parent as? GoAssignmentStatement ?: return false
            if (assignment.assignOp.assign == null) return false
            if (!topLevel(assignment)) return false
            val others = PsiTreeUtil.findChildrenOfType(assignment, GoReferenceExpression::class.java).filter { it !== ref && it.expression == null }
            return others.none { o -> service.resolve(o).firstOrNull() == v }
        }

        private fun unconditionalDefinition(def: PsiElement, v: GoNamedElement): Boolean {
            val declaration = def.parent as? GoShortVarDeclaration ?: return false
            if (!topLevel(declaration)) return false
            return declaration.expressionList.none { e ->
                (PsiTreeUtil.findChildrenOfType(e, GoReferenceExpression::class.java) + listOfNotNull(e as? GoReferenceExpression)).any { service.resolve(it).firstOrNull() == v }
            }
        }

        private fun topLevel(statement: PsiElement): Boolean {
            val s = if (statement.parent is GoSimpleStatement) statement.parent else statement
            return statements.any { it === s }
        }

        /** Whether a value [v] gets in the selection may be read after it. */
        private fun usedAfter(v: GoNamedElement, writes: List<GoFlowAccess>, escaping: Boolean, input: Boolean): Boolean {
            val f = flow
            val loop = enclosingLoop()
            if (f != null && v in f.namedResults) return true
            // in a loop the next run of the selection reads what this one wrote, whenever it reads the variable before writing it
            if (loop != null && input) return true
            if (f == null || !f.isTracked(v) || escaping) {
                return refsTo(v, top).any { it.textRange.startOffset >= range.endOffset || loop != null && loop.textRange.contains(it.textRange) && !inside(it) }
            }
            val seen = HashSet<GoFlowAccess>()
            val queue = ArrayDeque(writes)
            while (queue.isNotEmpty()) {
                for (next in f.nextAccesses(queue.removeFirst())) {
                    if (next.isWrite || !seen.add(next)) continue
                    if (!inside(next.element)) return true
                    queue.addLast(next)
                }
            }
            return false
        }

        /** The `for` of the selection's function around it: the selection may run again and see its own writes. */
        private fun enclosingLoop(): GoForStatement? {
            var p: PsiElement? = anchor.parent
            while (p != null && p !== owner) {
                if (p is GoForStatement) return p
                p = p.parent
            }
            return null
        }

        private fun refsTo(v: GoNamedElement, scope: PsiElement): List<GoReferenceExpression> =
            PsiTreeUtil.findChildrenOfType(scope, GoReferenceExpression::class.java).filter { it.expression == null && it.identifier.text == v.name && service.resolve(it).firstOrNull() == v }

        /** Variables declared at the top of the selection and read after it: results declared at the call site. */
        private fun declaredInsideUsedAfter(): List<Use> {
            val out = ArrayList<Use>()
            val after = TextRange(range.endOffset, owner.textRange.endOffset)
            val names = statements.flatMap { GoPsiUtil.declarationsOf(it) }
            for (d in names) {
                val name = d.name ?: continue
                if (name == "_") continue
                val used = PsiTreeUtil.findChildrenOfType(owner, GoReferenceExpression::class.java)
                    .any { it.expression == null && after.contains(it.textRange) && it.identifier.text == name && service.resolve(it).firstOrNull() == d }
                val typeUsed = d is GoTypeSpec && PsiTreeUtil.findChildrenOfType(owner, GoTypeReferenceExpression::class.java)
                    .any { after.contains(it.textRange) && service.resolve(it) == d }
                if (!used && !typeUsed) continue
                if (d !is GoVarDefinition) refuse("'$name' is declared in the selection and used after it")
                out += Use(d, d.textRange.startOffset).also { it.output = true; it.declaredInside = true }
            }
            return out
        }

        // --- control flow ---

        private fun checkJumps() {
            for (e in elements) {
                val all = PsiTreeUtil.findChildrenOfAnyType(e, false, GoBreakStatement::class.java, GoContinueStatement::class.java, GoGotoStatement::class.java,
                    GoFallthroughStatement::class.java, GoDeferStatement::class.java)
                for (s in all) {
                    if (insideLiteral(s)) continue
                    when (s) {
                        is GoDeferStatement -> refuse("The selection contains 'defer': it would run when the new function returns")
                        is GoFallthroughStatement -> if (!inside(s.parent)) refuse("'fallthrough' leaves the selection")
                        is GoGotoStatement -> { val l = labelDefinition(s.labelRef?.identifier?.text); if (l == null || !inside(l)) refuse("'goto' leaves the selection") }
                        is GoBreakStatement -> jumpTarget(s, s.labelRef?.identifier?.text, continues = false).let { if (it == null || !inside(it)) refuse("'break' leaves the selection") }
                        is GoContinueStatement -> jumpTarget(s, s.labelRef?.identifier?.text, continues = true).let { if (it == null || !inside(it)) refuse("'continue' leaves the selection") }
                    }
                }
                for (l in PsiTreeUtil.findChildrenOfType(e, GoLabeledStatement::class.java)) {
                    val name = l.labelDefinition?.name ?: continue
                    val outside = PsiTreeUtil.findChildrenOfType(owner, io.github.golangsupport.lang.psi.GoLabelRef::class.java).any { it.identifier.text == name && !inside(it) }
                    if (outside) refuse("The label '$name' is used outside the selection")
                }
            }
        }

        private fun labelDefinition(name: String?): GoLabeledStatement? =
            name?.let { n -> PsiTreeUtil.findChildrenOfType(owner, GoLabeledStatement::class.java).firstOrNull { it.labelDefinition?.name == n && GoPsiUtil.functionOwner(it) == owner } }

        /** The statement a `break` / `continue` leaves: the labeled one or the innermost `for` (and `switch` / `select` for `break`). */
        private fun jumpTarget(s: PsiElement, label: String?, continues: Boolean): PsiElement? {
            var p: PsiElement? = s.parent
            while (p != null && p !is GoFunctionLit && p !is GoFunctionOrMethodDeclaration) {
                if (label != null) { if (p is GoLabeledStatement && p.labelDefinition?.name == label) return p }
                else if (p is GoForStatement || !continues && (p is GoExprSwitchStatement || p is GoTypeSwitchStatement || p is GoSelectStatement)) return p
                p = p.parent
            }
            return null
        }

        /** True when the call should be `return extracted(…)`; refuses a `return` on some paths only, and a bare one with named results. */
        private fun returnMode(): Boolean {
            val returns = statements.flatMap { PsiTreeUtil.findChildrenOfType(it, GoReturnStatement::class.java) + listOfNotNull(it as? GoReturnStatement) }.filter { !insideLiteral(it) }
            val results = service.enclosingResultTypes(anchor).orEmpty()
            if (results.isNotEmpty() && returns.any { it.expressionList.isEmpty() }) refuse("The selection has a bare 'return' of named results")
            val terminating = isTerminating(statements.last())
            if (returns.isNotEmpty() && !terminating) refuse("Not every path of the selection ends in 'return'")
            return terminating && (returns.isNotEmpty() || results.isNotEmpty())
        }

        private fun endsBody(): Boolean = (owner.let { it as? GoFunctionOrMethodDeclaration }?.block ?: PsiTreeUtil.getChildOfType(owner, GoBlock::class.java))
            ?.let { b -> statementsOf(b).lastOrNull() === statements.last() } ?: false

        /** The spec's terminating statements, without labels: `return`, `goto`, `panic(…)`, and blocks / `if` / `for` / `switch` / `select` built of them. */
        private fun isTerminating(s: PsiElement?): Boolean = when (s) {
            null -> false
            is GoReturnStatement, is GoGotoStatement -> true
            is GoSimpleStatement -> (s.leftHandExprList?.expressionList?.singleOrNull() as? GoCallExpr)?.let { call ->
                (call.expression as? GoReferenceExpression)?.let { it.expression == null && it.identifier.text == "panic" && service.resolve(it).none { r -> r.containingFile == file } }
            } == true
            is GoBlock -> isTerminating(statementsOf(s).lastOrNull())
            is GoLabeledStatement -> isTerminating(s.statement)
            is GoIfStatement -> {
                val els = PsiTreeUtil.getChildOfType(s, GoElseStatement::class.java)
                els != null && isTerminating(PsiTreeUtil.getChildOfType(s, GoBlock::class.java)) && isTerminating(els.children.firstOrNull { it is GoStatement })
            }
            is GoForStatement -> s.condition == null && s.rangeClause == null && s.forClause?.expression == null && !hasBreak(s)
            is GoExprSwitchStatement, is GoTypeSwitchStatement, is GoSelectStatement -> {
                val clauses = s.children.filter { it is GoExprCaseClause || it is GoTypeCaseClause || it is GoCommClause }
                val hasDefault = clauses.any { c -> c.node.findChildByType(GoTypes.DEFAULT) != null || (c as? GoCommClause)?.commCase?.default != null }
                (hasDefault || s is GoSelectStatement) && !hasBreak(s) &&
                    clauses.all { c -> statementsOf(c).lastOrNull().let { it is GoFallthroughStatement || isTerminating(it) } }
            }
            else -> false
        }

        private fun hasBreak(target: PsiElement): Boolean =
            PsiTreeUtil.findChildrenOfType(target, GoBreakStatement::class.java).any { !insideLiteral(it) && jumpTarget(it, it.labelRef?.identifier?.text, false) === target } ||
                (target.parent as? GoLabeledStatement)?.let { l -> PsiTreeUtil.findChildrenOfType(target, GoBreakStatement::class.java).any { b -> b.labelRef?.identifier?.text == l.labelDefinition?.name } } == true

        // --- call site, names, type parameters, text ---

        /** `x, y = f()`, `a, b := f()`; mixing outer variables and new ones uses `:=` only when the outer ones live in the same scope. */
        private fun callWithResults(outputs: List<Use>, invocation: String, indent: String): String {
            val lhs = outputs.joinToString(", ") { it.variable.name!! }
            val fresh = outputs.filter { it.declaredInside }
            if (fresh.isEmpty()) return "$lhs = $invocation"
            if (fresh.size == outputs.size || outputs.filter { !it.declaredInside }.all { sameScope(it.variable) }) return "$lhs := $invocation"
            val decls = fresh.joinToString("") { "var ${it.variable.name} ${source.type(typeOf(it.variable))}\n$indent" }
            return "$decls$lhs = $invocation"
        }

        /** Whether [v] is declared in the block of the selection (parameters count for the body of their function). */
        private fun sameScope(v: GoNamedElement): Boolean {
            val container = statements.first().parent
            if (v is GoParamDefinition || v is GoReceiver) return container === (owner as? GoFunctionOrMethodDeclaration)?.block || container.parent === owner
            var p: PsiElement? = v.parent
            while (p != null && p !is GoStatement) p = p.parent
            val statement = if (p?.parent is GoSimpleStatement) p.parent else p
            return statement?.parent === container
        }

        private fun freeName(receiver: GoReceiver?): String {
            val receiverType = receiver?.let { service.declarationType(it) }
            val packageScope = file.packageClause ?: file
            return GoExtraction.unique(DEFAULT_NAME) { n ->
                GoUniverse.isBuiltin(n) || GoScopes.resolveName(packageScope, n).isNotEmpty() ||
                    receiverType != null && service.lookupFieldOrMethod(receiverType, n) != null
            }
        }

        /** `T any, K comparable` for the enclosing function's type parameters the new function needs. */
        private fun typeParameters(types: List<GoType>, isMethod: Boolean): List<String> {
            val used = LinkedHashSet<GoTypeParamDefinition>()
            for (t in types) walk(t) { if (it is GoTypeParamType) used += it.declaration }
            for (e in elements) for (tr in PsiTreeUtil.findChildrenOfType(e, GoTypeReferenceExpression::class.java)) {
                (service.resolve(tr) as? GoTypeParamDefinition)?.let { used += it }
            }
            if (used.isEmpty()) return emptyList()
            val own = top.typeParameters?.typeParameterDeclarationList.orEmpty()
            val ownDefs = own.flatMap { it.typeParamDefinitionList }
            if (used.any { it !in ownDefs }) {
                if (isMethod) used.retainAll(ownDefs.toSet()) else refuse("The selection uses the type parameters of the receiver")
            }
            // constraints may name other type parameters: `[S ~[]E, E any]`
            var grew = true
            while (grew) {
                grew = false
                for (d in own) if (d.typeParamDefinitionList.any { it in used }) {
                    for (tr in PsiTreeUtil.findChildrenOfType(d.constraintElem, GoTypeReferenceExpression::class.java)) {
                        val r = service.resolve(tr) as? GoTypeParamDefinition ?: continue
                        if (r in ownDefs && used.add(r)) grew = true
                    }
                }
            }
            return ownDefs.filter { it in used }.map { "${it.name} ${(it.parent as GoTypeParameterDeclaration).constraintElem?.text ?: "any"}" }
        }

        /** The selected text re-indented one tab deep (an expression keeps its first line); lines inside raw strings stay as they are. */
        private fun reindent(single: PsiElement?): String {
            val text = file.viewProvider.contents
            val base = GoIntentionText.indentAt(text, range.startOffset)
            val raw = ArrayList<TextRange>()
            var leaf = file.findElementAt(range.startOffset)
            while (leaf != null && leaf.textRange.startOffset < range.endOffset) {
                if (leaf.node.elementType == GoTypes.RAW_STRING) raw += leaf.textRange
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
            val from = if (single != null) range.startOffset else text.lastIndexOf('\n', range.startOffset - 1) + 1
            val lines = ArrayList<String>()
            var o = from
            while (true) {
                val nl = text.indexOf('\n', o)
                val lineEnd = if (nl < 0 || nl >= range.endOffset) range.endOffset else nl
                val line = text.subSequence(o, lineEnd).toString()
                lines += when {
                    o == from && single != null -> line
                    raw.any { it.startOffset < o && o < it.endOffset } -> line
                    line.isBlank() -> ""
                    line.startsWith(base) -> "\t" + line.substring(base.length)
                    else -> "\t" + line.trimStart()
                }
                if (lineEnd >= range.endOffset) break
                o = lineEnd + 1
            }
            return lines.joinToString("\n")
        }
    }

    /** Visits [t] and the types it is built of. */
    fun walk(t: GoType, visit: (GoType) -> Unit) {
        visit(t)
        when (t) {
            is GoArrayType -> walk(t.elem, visit)
            is GoSliceType -> walk(t.elem, visit)
            is GoPointerType -> walk(t.elem, visit)
            is GoChanType -> walk(t.elem, visit)
            is GoMapType -> { walk(t.key, visit); walk(t.value, visit) }
            is GoTupleType -> t.types.forEach { walk(it, visit) }
            is GoSignatureType -> { t.params.forEach { walk(it.type, visit) }; t.results.forEach { walk(it.type, visit) } }
            is GoStructType -> t.fields.forEach { walk(it.type, visit) }
            is GoNamedType -> t.typeArgs.forEach { walk(it, visit) }
            else -> {}
        }
    }
}
