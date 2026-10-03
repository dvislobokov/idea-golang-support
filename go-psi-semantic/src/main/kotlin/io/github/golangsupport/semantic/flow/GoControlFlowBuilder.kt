package io.github.golangsupport.semantic.flow

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.check.GoTerminating
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.recvStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Builds a [GoControlFlow] with "pending edges": the builder keeps the dangling exits of what it emitted last ([pending]) and
 * connects them to the next node it adds. Conditions return their true and false exits separately, which is how `&&` / `||`
 * short-circuit; `break`, `continue`, `goto` and `fallthrough` move the pending exits to their target's list.
 */
internal class GoControlFlowBuilder(private val owner: PsiElement, private val body: GoBlock) {

    /** Thrown for constructs the graph does not model; [build] returns null. */
    private class Unsupported(message: String) : RuntimeException(message, null, false, false)

    private class Pending(val from: GoFlowNode, val kind: GoFlowEdge.Kind)

    /** A statement `break` / `continue` can target. */
    private class Target(val statement: PsiElement, val label: String?, val isLoop: Boolean) {
        val breaks = ArrayList<Pending>()
        val continues = ArrayList<Pending>()
    }

    private val project = owner.project
    private val resolver = GoResolver.getInstance(project)
    private val typer = GoExpressionTyper.getInstance(project)
    private val terminating = GoTerminating { call -> GoTerminating.isPanicCallee(call, ::isBuiltinCallee) }

    private val nodes = ArrayList<GoFlowNode>()
    private var pending = ArrayList<Pending>()
    private val targets = ArrayList<Target>()
    private val labelNodes = HashMap<String, GoFlowNode>()
    private val labelStatements = HashMap<String, GoLabeledStatement>()
    private val forwardGotos = HashMap<String, MutableList<Pending>>()
    private val gotoStatements = ArrayList<GoGotoStatement>()
    private var fallthroughs = ArrayList<Pending>()
    private val nodeByElement = HashMap<PsiElement, GoFlowNode>()
    private val accessByElement = HashMap<PsiElement, MutableList<GoFlowAccess>>()
    private val defers = ArrayList<GoDeferStatement>()

    private val variables = LinkedHashSet<GoNamedElement>()
    private val escaping = HashSet<GoNamedElement>()
    private val canonical = HashMap<GoNamedElement, GoNamedElement>()
    private val unresolvedNames = HashSet<String>()
    private val namedResults = ArrayList<GoNamedElement>()

    private lateinit var deferred: GoFlowNode
    private lateinit var exit: GoFlowNode

    fun build(): GoControlFlow? = try {
        if (PsiTreeUtil.hasErrorElements(body) || hasErrorElementsInSignature()) null else doBuild()
    } catch (e: Unsupported) {
        null
    }

    private fun hasErrorElementsInSignature(): Boolean {
        val signature = signatureOf(owner) ?: return false
        return PsiTreeUtil.findChildOfType(signature, PsiErrorElement::class.java) != null
    }

    private fun doBuild(): GoControlFlow {
        val entry = newNode(GoFlowNode.Kind.ENTRY, null)
        deferred = GoFlowNode(-1, GoFlowNode.Kind.DEFERRED, null)
        exit = GoFlowNode(-1, GoFlowNode.Kind.EXIT, null)
        entry.accesses = entryDefinitions(entry)
        pending = arrayListOf(Pending(entry, GoFlowEdge.Kind.NORMAL))
        statements(body.statementList)
        if (pending.isNotEmpty()) {
            val end = add(GoFlowNode.Kind.RETURN, body)
            end.accesses = emptyList()
            connect(end, deferred)
            pending = ArrayList()
        }
        for ((label, list) in forwardGotos) if (list.isNotEmpty()) throw Unsupported("goto $label: no label")
        for (g in gotoStatements) checkGoto(g)
        // DEFERRED and EXIT go last so that node order follows the source.
        val deferredNode = GoFlowNode(nodes.size, GoFlowNode.Kind.DEFERRED, null).also { nodes += it }
        val exitNode = GoFlowNode(nodes.size, GoFlowNode.Kind.EXIT, null).also { nodes += it }
        retarget(deferred, deferredNode)
        retarget(exit, exitNode)
        edge(deferredNode, exitNode, GoFlowEdge.Kind.NORMAL)
        if (nodes.size > MAX_NODES) throw Unsupported("too many nodes")
        for (v in variables) if (v.name in unresolvedNames) escaping += v
        val flow = GoControlFlow(
            owner, body, nodes, entry, deferredNode, exitNode, variables.toList(), namedResults, defers, escaping,
            nodeByElement, accessByElement,
        )
        flow.accesses.forEachIndexed { i, a -> a.index = i }
        return flow
    }

    /** Edges recorded against the placeholder [from] (built before the real node existed) now end at [to]. */
    private fun retarget(from: GoFlowNode, to: GoFlowNode) {
        for (e in from.pred) {
            val real = GoFlowEdge(e.from, to, e.kind)
            e.from.succ[e.from.succ.indexOf(e)] = real
            to.pred += real
        }
    }

    // --- nodes and edges ---

    private fun newNode(kind: GoFlowNode.Kind, element: PsiElement?): GoFlowNode {
        val node = GoFlowNode(nodes.size, kind, element)
        nodes += node
        if (element != null && element !== body) nodeByElement.putIfAbsent(element, node)
        if (nodes.size > MAX_NODES) throw Unsupported("too many nodes")
        return node
    }

    /** Adds a node, connects [pending] to it and makes it the only pending exit. */
    private fun add(kind: GoFlowNode.Kind, element: PsiElement?): GoFlowNode {
        val node = newNode(kind, element)
        for (p in pending) edge(p.from, node, p.kind)
        pending = arrayListOf(Pending(node, GoFlowEdge.Kind.NORMAL))
        return node
    }

    private fun edge(from: GoFlowNode, to: GoFlowNode, kind: GoFlowEdge.Kind) {
        val e = GoFlowEdge(from, to, kind)
        from.succ += e
        to.pred += e
    }

    private fun connect(from: GoFlowNode, to: GoFlowNode) = edge(from, to, GoFlowEdge.Kind.NORMAL)

    private fun connectPending(list: List<Pending>, to: GoFlowNode) {
        for (p in list) edge(p.from, to, p.kind)
    }

    // --- statements ---

    private fun statements(list: List<GoStatement>) = list.forEach { statement(it, null) }

    private fun statement(s: GoStatement?, label: String?) {
        when (s) {
            null -> {}
            is GoBlock -> statements(s.statementList)
            is GoLabeledStatement -> labeled(s)
            is GoIfStatement -> ifStatement(s)
            is GoForStatement -> forStatement(s, label)
            is GoExprSwitchStatement -> exprSwitch(s, label)
            is GoTypeSwitchStatement -> typeSwitch(s, label)
            is GoSelectStatement -> select(s, label)
            is GoReturnStatement -> returnStatement(s)
            is GoBreakStatement -> pending.let { p -> target(s.labelRef, s, loopOnly = false).breaks += p; pending = ArrayList() }
            is GoContinueStatement -> pending.let { p -> target(s.labelRef, s, loopOnly = true).continues += p; pending = ArrayList() }
            is GoGotoStatement -> gotoStatement(s)
            is GoFallthroughStatement -> { fallthroughs.addAll(pending); pending = ArrayList() }
            is GoConstDeclaration, is GoTypeDeclaration -> {}
            is GoDeferStatement -> { defers += s; simple(s) }
            is GoSimpleStatement -> expressionStatement(s)
            else -> simple(s)
        }
    }

    /** A statement evaluated as one node. */
    private fun simple(s: PsiElement) {
        val node = add(GoFlowNode.Kind.STATEMENT, s)
        node.accesses = AccessCollector(node).also { it.statement(s) }.result
    }

    private fun expressionStatement(s: GoSimpleStatement) {
        s.statement?.let { return statement(it, null) }
        val call = unparen(s.expressions.singleOrNull()) as? GoCallExpr
        val kind = call?.let(::exitKind) ?: GoFlowNode.Kind.STATEMENT
        val node = add(kind, s)
        node.accesses = AccessCollector(node).also { it.statement(s) }.result
        when (kind) {
            GoFlowNode.Kind.PANIC -> { connect(node, deferred); pending = ArrayList() }
            GoFlowNode.Kind.TERMINATE -> { connect(node, exit); pending = ArrayList() }
            else -> {}
        }
    }

    private fun returnStatement(s: GoReturnStatement) {
        val node = add(GoFlowNode.Kind.RETURN, s)
        val collector = AccessCollector(node)
        s.expressionList.forEach(collector::read)
        if (s.expressionList.isEmpty()) {
            for (r in namedResults) collector.add(r, GoFlowAccess.Kind.READ, s, null, -1, false)
        } else if (namedResults.isNotEmpty()) {
            val values = s.expressionList
            namedResults.forEachIndexed { i, r ->
                val (value, index) = when {
                    values.size == namedResults.size -> values[i] to -1
                    values.size == 1 -> values[0] to i
                    else -> null to -1
                }
                collector.add(r, GoFlowAccess.Kind.WRITE, s, value, index, false)
            }
        }
        node.accesses = collector.result
        connect(node, deferred)
        pending = ArrayList()
    }

    private fun labeled(s: GoLabeledStatement) {
        val name = s.labelDefinition?.name ?: throw Unsupported("label without name")
        val node = add(GoFlowNode.Kind.JOIN, s)
        labelNodes[name] = node
        labelStatements[name] = s
        forwardGotos.remove(name)?.let { connectPending(it, node) }
        statement(s.statement, name)
    }

    private fun gotoStatement(s: GoGotoStatement) {
        val name = s.labelRef?.identifier?.text ?: throw Unsupported("goto without label")
        gotoStatements += s
        val node = labelNodes[name]
        if (node != null) connectPending(pending, node) else forwardGotos.getOrPut(name) { ArrayList() }.addAll(pending)
        pending = ArrayList()
    }

    /** Go forbids a jump into a block; when the label's block does not enclose the goto, the graph would be wrong. */
    private fun checkGoto(g: GoGotoStatement) {
        val label = labelStatements[g.labelRef?.identifier?.text] ?: throw Unsupported("goto without label")
        if (!PsiTreeUtil.isAncestor(label.parent, g, true)) throw Unsupported("goto into a block")
    }

    private fun target(ref: GoLabelRef?, s: GoStatement, loopOnly: Boolean): Target {
        val name = ref?.identifier?.text
        val t = if (name == null) targets.lastOrNull { !loopOnly || it.isLoop } else targets.lastOrNull { it.label == name }
        if (t == null || loopOnly && !t.isLoop) throw Unsupported("no target for ${s.text}")
        return t
    }

    private fun ifStatement(s: GoIfStatement) {
        statement(s.initStatement, null)
        val cond = s.condition ?: throw Unsupported("if without condition")
        val (onTrue, onFalse) = condition(cond)
        pending = onTrue
        statement(s.block, null)
        val afterThen = pending
        pending = onFalse
        s.elseStatement?.let { statement(it.statement, null) }
        pending.addAll(afterThen)
    }

    /**
     * Emits the atomic conditions of [expr]; returns the pending exits taken when it is true and when it is false.
     */
    private fun condition(expr: GoExpression): Pair<ArrayList<Pending>, ArrayList<Pending>> {
        when (val e = unparen(expr)) {
            is GoAndExpr -> {
                val (lt, lf) = condition(e.left ?: throw Unsupported("&&"))
                pending = lt
                val (rt, rf) = condition(e.right ?: throw Unsupported("&&"))
                rf.addAll(lf)
                return rt to rf
            }
            is GoOrExpr -> {
                val (lt, lf) = condition(e.left ?: throw Unsupported("||"))
                pending = lf
                val (rt, rf) = condition(e.right ?: throw Unsupported("||"))
                rt.addAll(lt)
                return rt to rf
            }
            is GoUnaryExpr -> if (e.not != null) {
                val (t, f) = condition(e.expression ?: throw Unsupported("!"))
                return f to t
            }
            else -> {}
        }
        val node = add(GoFlowNode.Kind.CONDITION, expr)
        node.accesses = AccessCollector(node).also { it.read(expr) }.result
        pending = ArrayList()
        return arrayListOf(Pending(node, GoFlowEdge.Kind.TRUE)) to arrayListOf(Pending(node, GoFlowEdge.Kind.FALSE))
    }

    private fun forStatement(s: GoForStatement, label: String?) {
        val range = s.rangeClause
        val target = Target(s, label, isLoop = true)
        if (range != null) {
            range.expression?.let { x ->
                val node = add(GoFlowNode.Kind.STATEMENT, x)
                node.accesses = AccessCollector(node).also { it.read(x) }.result
            }
            val head = add(GoFlowNode.Kind.RANGE, range)
            head.accesses = AccessCollector(head).also { it.rangeVariables(range) }.result
            targets += target
            statement(s.block, null)
            targets.removeLast()
            connectPending(pending + target.continues, head)
            pending = arrayListOf(Pending(head, GoFlowEdge.Kind.NORMAL))
            pending.addAll(target.breaks)
            return
        }
        val clause = s.forClause
        val init = clause?.initStatement
        statement(init, null)
        val head = add(GoFlowNode.Kind.JOIN, null)
        val cond = if (clause != null) clause.expression else s.condition
        var exits = ArrayList<Pending>()
        if (cond != null) {
            val (t, f) = condition(cond)
            pending = t
            exits = f
        }
        targets += target
        statement(s.block, null)
        targets.removeLast()
        pending.addAll(target.continues)
        clause?.statementList?.firstOrNull { it !== init }?.let { statement(it, null) }
        connectPending(pending, head)
        pending = exits
        pending.addAll(target.breaks)
    }

    private fun exprSwitch(s: GoExprSwitchStatement, label: String?) {
        statement(s.initStatement, null)
        val tag = s.tag
        if (tag != null) simpleExpression(tag)
        val clauses = s.exprCaseClauseList
        val entries = HashMap<GoExprCaseClause, ArrayList<Pending>>()
        for (c in clauses) {
            if (c.default != null) continue
            val values = c.expressionList
            if (values.isEmpty()) throw Unsupported("case without values")
            if (tag == null) {
                // case a, b: is a || b
                val matched = ArrayList<Pending>()
                for (v in values) {
                    val (t, f) = condition(v)
                    matched.addAll(t)
                    pending = f
                }
                entries[c] = matched
            } else {
                val node = add(GoFlowNode.Kind.CASE, c)
                node.accesses = AccessCollector(node).also { col -> values.forEach(col::read) }.result
                entries[c] = arrayListOf(Pending(node, GoFlowEdge.Kind.TRUE))
                pending = arrayListOf(Pending(node, GoFlowEdge.Kind.FALSE))
            }
        }
        val noMatch = pending
        clauses.firstOrNull { it.default != null }?.let { entries[it] = noMatch }
        val after = if (clauses.any { it.default != null }) ArrayList() else ArrayList(noMatch)
        clauseBodies(s, label, clauses, after, { entries[it] ?: ArrayList() }, { it.statementList }, allowFallthrough = true)
    }

    private fun typeSwitch(s: GoTypeSwitchStatement, label: String?) {
        statement(s.initStatement, null)
        val guard = s.guard ?: throw Unsupported("type switch without guard")
        guard.expression?.let { simpleStatementNode(guard) { col -> col.read(it) } } ?: throw Unsupported("guard")
        val binding = guard.varDefinition
        val clauses = s.typeCaseClauseList
        val entries = HashMap<GoTypeCaseClause, ArrayList<Pending>>()
        for (c in clauses) {
            if (c.default != null) continue
            val test = c.type ?: throw Unsupported("case without types")
            val node = add(GoFlowNode.Kind.CASE, test)
            entries[c] = arrayListOf(Pending(node, GoFlowEdge.Kind.TRUE))
            pending = arrayListOf(Pending(node, GoFlowEdge.Kind.FALSE))
        }
        val noMatch = pending
        val hasDefault = clauses.any { it.default != null }
        clauses.firstOrNull { it.default != null }?.let { entries[it] = noMatch }
        val after = if (hasDefault) ArrayList() else ArrayList(noMatch)
        clauseBodies(s, label, clauses, after, { c ->
            val entry = entries[c] ?: ArrayList()
            if (binding == null || binding.name == "_") entry
            else {
                pending = entry
                val bind = add(GoFlowNode.Kind.CASE, c)
                bind.accesses = AccessCollector(bind).also { it.define(binding, c, null, -1) }.result
                pending
            }
        }, { it.statementList }, allowFallthrough = false)
    }

    private fun select(s: GoSelectStatement, label: String?) {
        val head = add(GoFlowNode.Kind.JOIN, s)
        val clauses = s.commClauseList
        clauseBodies(s, label, clauses, ArrayList(), { c ->
            pending = arrayListOf(Pending(head, GoFlowEdge.Kind.NORMAL))
            val case = c.commCase ?: throw Unsupported("comm clause")
            if (case.default == null) {
                val node = add(GoFlowNode.Kind.COMM, case)
                node.accesses = AccessCollector(node).also { col ->
                    when (val st = case.statement) {
                        is GoRecvStatement -> col.receive(st)
                        null -> {}
                        else -> col.statement(st)
                    }
                }.result
            }
            pending
        }, { it.statementList }, allowFallthrough = false)
    }

    /** Clause bodies in source order, each reached from [entry]; `fallthrough` continues into the next body. */
    private fun <C : PsiElement> clauseBodies(
        s: GoStatement, label: String?, clauses: List<C>, after: ArrayList<Pending>,
        entry: (C) -> ArrayList<Pending>, statements: (C) -> List<GoStatement>, allowFallthrough: Boolean,
    ) {
        val target = Target(s, label, isLoop = false)
        targets += target
        var carried = ArrayList<Pending>()
        for (c in clauses) {
            pending = entry(c)
            pending.addAll(carried)
            carried = ArrayList()
            val saved = fallthroughs
            fallthroughs = ArrayList()
            val list = statements(c)
            list.forEachIndexed { i, st -> if (st is GoFallthroughStatement && (!allowFallthrough || i != list.lastIndex)) throw Unsupported("misplaced fallthrough") }
            statements(list)
            carried = fallthroughs
            fallthroughs = saved
            after.addAll(pending)
        }
        if (carried.isNotEmpty()) throw Unsupported("fallthrough in the last clause")
        targets.removeLast()
        pending = after
        pending.addAll(target.breaks)
    }

    private fun simpleExpression(x: GoExpression) {
        val node = add(GoFlowNode.Kind.STATEMENT, x)
        node.accesses = AccessCollector(node).also { it.read(x) }.result
    }

    private inline fun simpleStatementNode(element: PsiElement, fill: (AccessCollector) -> Unit) {
        val node = add(GoFlowNode.Kind.STATEMENT, element)
        node.accesses = AccessCollector(node).also(fill).result
    }

    // --- calls that do not return ---

    /** [GoFlowNode.Kind.PANIC] / [GoFlowNode.Kind.TERMINATE] for a call that never returns normally, else null. */
    private fun exitKind(call: GoCallExpr): GoFlowNode.Kind? {
        if (terminating.isTerminating(call.parent?.parent as? GoSimpleStatement)) return GoFlowNode.Kind.PANIC
        val callee = unparen(call.expression) as? GoReferenceExpression ?: return null
        val name = callee.referenceName ?: return null
        if (callee.qualifier == null) return null
        if (name !in EXIT_NAMES) return null
        val target = resolver.resolveReferenceExpression(callee).firstOrNull()?.element as? GoNamedElement ?: return null
        val file = target.containingFile as? GoFile ?: return null
        val path = GoPackageModel.getInstance(project).packagePathOf(file)
        return when {
            target is GoFunctionDeclaration && path == "os" && name == "Exit" -> GoFlowNode.Kind.TERMINATE
            target is GoFunctionDeclaration && path == "runtime" && name == "Goexit" -> GoFlowNode.Kind.PANIC
            (target is GoFunctionDeclaration || target is GoMethodDeclaration) && path == "log" && name.startsWith("Fatal") -> GoFlowNode.Kind.TERMINATE
            (target is GoFunctionDeclaration || target is GoMethodDeclaration) && path == "log" && name.startsWith("Panic") -> GoFlowNode.Kind.PANIC
            (target is GoMethodDeclaration || target is GoMethodSpec) && path == "testing" && name in TESTING_EXITS -> GoFlowNode.Kind.PANIC
            else -> null
        }
    }

    private fun isBuiltinCallee(c: GoReferenceExpression): Boolean {
        val results = resolver.resolveReferenceExpression(c)
        return results.isEmpty() && (c.referenceName ?: "") in GoUniverse.FUNCTIONS ||
            results.any { val e = it.element; e is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(e) }
    }

    // --- variables ---

    private fun entryDefinitions(entry: GoFlowNode): List<GoFlowAccess> {
        val collector = AccessCollector(entry)
        (owner as? GoMethodDeclaration)?.receiver?.let { r -> if (r.name != null && r.name != "_") collector.define(r, r, null, -1) }
        val signature = signatureOf(owner)
        for (d in signature?.parameters?.parameterDeclarationList.orEmpty()) for (p in d.paramDefinitionList) collector.define(p, p, null, -1)
        for (d in signature?.result?.parameters?.parameterDeclarationList.orEmpty()) for (p in d.paramDefinitionList) {
            collector.define(p, p, null, -1)
            if (p.name != "_") namedResults += p
        }
        return collector.result
    }

    private fun signatureOf(e: PsiElement): GoSignature? = when (e) {
        is GoFunctionOrMethodDeclaration -> e.signature
        is GoFunctionLit -> e.signature
        else -> null
    }

    /** Whether [def] is a variable declared by this function itself (not an enclosing one, not a nested literal). */
    private fun isOwnVariable(def: PsiElement): Boolean =
        (def is GoVarDefinition || def is GoParamDefinition || def is GoReceiver) && GoPsiUtil.functionOwner(def) === owner

    /** The first declaration of the variable [def] declares or redeclares (`a, err := …` after `b, err := …` in the same scope). */
    private fun canonicalOf(def: GoNamedElement): GoNamedElement = canonical.getOrPut(def) {
        if (def !is GoVarDefinition) return@getOrPut def
        val stmt = def.parent as? GoShortVarDeclaration ?: return@getOrPut def
        val name = def.name ?: return@getOrPut def
        if (name == "_") return@getOrPut def
        val holder = if (stmt.parent is GoLabeledStatement) stmt.parent else stmt
        val container = holder.parent
        val list: List<GoStatement> = when (container) {
            is GoBlock -> container.statementList
            is GoExprCaseClause -> container.statementList
            is GoTypeCaseClause -> container.statementList
            is GoCommClause -> container.statementList
            else -> return@getOrPut def
        }
        val earlier = list.takeWhile { it !== holder }.flatMap(GoPsiUtil::declarationsOf).lastOrNull { it.name == name }
        if (earlier is GoVarDefinition) return@getOrPut canonicalOf(earlier)
        if (earlier != null) return@getOrPut def
        when (container) {
            is GoTypeCaseClause -> (container.parent as? GoTypeSwitchStatement)?.guard?.varDefinition?.takeIf { it.name == name }?.let { return@getOrPut it }
            is GoCommClause -> container.recvStatement?.varDefinitionList?.firstOrNull { it.name == name }?.let { return@getOrPut it }
            is GoBlock -> if (container === body) {
                (owner as? GoMethodDeclaration)?.receiver?.takeIf { it.name == name }?.let { return@getOrPut it }
                val signature = signatureOf(owner)
                val params = signature?.parameters?.parameterDeclarationList.orEmpty() + signature?.result?.parameters?.parameterDeclarationList.orEmpty()
                params.flatMap { it.paramDefinitionList }.firstOrNull { it.name == name }?.let { return@getOrPut it }
            }
            else -> {}
        }
        def
    }

    /** The variable of this function [ref] names (marking ambiguous ones escaping), or null. */
    private fun variableOf(ref: GoReferenceExpression): GoNamedElement? {
        if (ref.qualifier != null) return null
        val name = ref.referenceName ?: return null
        if (name == "_") return null
        val results = resolver.resolveReferenceExpression(ref)
        if (results.isEmpty()) {
            if (name !in GoUniverse.ALL) unresolvedNames += name
            return null
        }
        val own = results.mapNotNull { r -> (r.element as? GoNamedElement)?.takeIf(::isOwnVariable)?.let(::canonicalOf) }.distinct()
        if (own.isEmpty()) return null
        own.forEach { variables += it }
        if (own.size > 1 || own.size != results.size) {
            escaping += own
            return null
        }
        return own.single()
    }

    /** Collects the accesses of one node in evaluation order. */
    private inner class AccessCollector(private val node: GoFlowNode) {
        val result = ArrayList<GoFlowAccess>()

        fun add(variable: GoNamedElement, kind: GoFlowAccess.Kind, element: PsiElement, value: GoExpression?, index: Int, compound: Boolean) {
            val access = GoFlowAccess(variable, kind, element, node, value, index, compound)
            result += access
            accessByElement.getOrPut(element) { ArrayList(1) } += access
        }

        fun define(def: GoNamedElement, element: PsiElement, value: GoExpression?, index: Int) {
            if (def.name == null || def.name == "_") return
            val v = canonicalOf(def)
            variables += v
            add(v, GoFlowAccess.Kind.DEFINE, element, value, index, false)
        }

        fun statement(s: PsiElement) {
            when (s) {
                is GoShortVarDeclaration -> {
                    s.expressionList.forEach(::read)
                    assignDefinitions(s.varDefinitionList, s.expressionList)
                }
                is GoVarDeclaration -> for (spec in s.varSpecList) {
                    spec.expressionList.forEach(::read)
                    assignDefinitions(spec.varDefinitionList, spec.expressionList)
                }
                is GoAssignmentStatement -> {
                    val lhs = s.leftHandExprList?.expressionList.orEmpty()
                    val rhs = s.expressionList
                    val plain = s.assignOp?.assign != null
                    rhs.forEach(::read)
                    for (l in lhs) lhsOperands(l)
                    lhs.forEachIndexed { i, l ->
                        val (value, index) = valueFor(rhs, lhs.size, i)
                        target(l, if (plain) value else null, if (plain) index else -1, compound = !plain)
                    }
                }
                is GoIncDecStatement -> s.leftHandExprList?.expressionList?.forEach { l -> lhsOperands(l); target(l, null, -1, compound = true) }
                is GoSendStatement -> { s.leftHandExprList?.expressionList?.forEach(::read); s.expression?.let(::read) }
                is GoSimpleStatement -> s.statement?.let(::statement) ?: s.expressions.forEach(::read)
                is GoDeferStatement -> s.expression?.let(::read)
                is GoGoStatement -> s.expression?.let(::read)
                else -> read(s)
            }
        }

        private fun assignDefinitions(defs: List<GoVarDefinition>, values: List<GoExpression>) {
            defs.forEachIndexed { i, d ->
                val (value, index) = valueFor(values, defs.size, i)
                define(d, d, value, index)
            }
        }

        private fun valueFor(values: List<GoExpression>, targets: Int, i: Int): Pair<GoExpression?, Int> = when {
            values.isEmpty() -> null to -1
            values.size == targets -> values[i] to -1
            values.size == 1 -> values[0] to i
            else -> null to -1
        }

        /** The operands an assignment target evaluates before the store: `x` in `x.f = v`, `a` and `i` in `a[i] = v`, `p` in `*p = v`. */
        private fun lhsOperands(l: GoExpression) {
            val e = unparen(l)
            if (e is GoReferenceExpression && e.qualifier == null) return
            read(l)
        }

        /** The store into an assignment target: a write when it is a variable of this function. */
        private fun target(l: GoExpression, value: GoExpression?, index: Int, compound: Boolean) {
            val e = unparen(l) as? GoReferenceExpression ?: return
            if (e.qualifier != null) return
            val v = variableOf(e) ?: return
            if (compound) add(v, GoFlowAccess.Kind.READ, e, null, -1, false)
            add(v, GoFlowAccess.Kind.WRITE, e, value, index, compound)
        }

        fun rangeVariables(range: GoRangeClause) {
            val defs = range.varDefinitionList
            if (defs.isNotEmpty()) {
                defs.forEach { define(it, it, null, -1) }
                return
            }
            val lhs = range.leftHandExprList?.expressionList.orEmpty()
            for (l in lhs) lhsOperands(l)
            for (l in lhs) target(l, null, -1, compound = false)
        }

        fun receive(st: GoRecvStatement) {
            st.expression?.let(::read)
            val defs = st.varDefinitionList
            if (defs.isNotEmpty()) {
                defs.forEach { define(it, it, null, -1) }
                return
            }
            val lhs = st.leftHandExprList?.expressionList.orEmpty()
            for (l in lhs) lhsOperands(l)
            for (l in lhs) target(l, null, -1, compound = false)
        }

        /** Reads of every variable in [e] (not inside nested function literals: their variables escape). */
        fun read(e: PsiElement) {
            when (e) {
                is GoFunctionLit -> { captured(e); return }
                is GoReferenceExpression -> {
                    val q = e.qualifier
                    if (q == null) {
                        variableOf(e)?.let { add(it, GoFlowAccess.Kind.READ, e, null, -1, false) }
                        return
                    }
                    read(q)
                    methodOnValue(e, q)
                    return
                }
                is GoUnaryExpr -> if (e.operator == GoTypes.AND) e.expression?.let(::addressTaken)
                is GoIndexOrSliceExpr -> if (e.isSlice) e.expression?.let(::slicedArray)
                else -> {}
            }
            var c = e.firstChild
            while (c != null) {
                if (c !is GoType || c is GoExpression) read(c)
                c = c.nextSibling
            }
        }

        /** Variables of this function referenced inside a nested literal are captured: escaping. */
        private fun captured(lit: GoFunctionLit) {
            for (ref in PsiTreeUtil.findChildrenOfType(lit, GoReferenceExpression::class.java)) {
                if (ref.qualifier != null) continue
                val name = ref.referenceName ?: continue
                if (name == "_") continue
                val results = resolver.resolveReferenceExpression(ref)
                if (results.isEmpty()) {
                    if (name !in GoUniverse.ALL) unresolvedNames += name
                    continue
                }
                for (r in results) {
                    val el = r.element as? GoNamedElement ?: continue
                    if (isOwnVariable(el)) canonicalOf(el).let { variables += it; escaping += it }
                }
            }
        }

        private fun rootVariable(x: GoExpression?): GoNamedElement? {
            var e: GoExpression? = x
            while (true) {
                e = when (val u = unparen(e)) {
                    is GoReferenceExpression -> u.qualifier ?: return variableOf(u)
                    is GoIndexOrSliceExpr -> u.expression
                    else -> return null
                }
            }
        }

        private fun addressTaken(x: GoExpression) {
            rootVariable(x)?.let { escaping += it }
        }

        private fun slicedArray(x: GoExpression) {
            val v = rootVariable(x) ?: return
            val type = typer.typeOf(x).underlying()
            if (type is GoArrayType || type is GoUnknownType) escaping += v
        }

        /** `x.M` with a method on an addressable non-pointer operand may take `&x`. */
        private fun methodOnValue(ref: GoReferenceExpression, q: GoExpression) {
            val v = rootVariable(q) ?: return
            val selection = resolver.resolveReferenceExpression(ref).firstNotNullOfOrNull { it as? GoResolver.Result.Selection }
            if (selection?.selection is GoLookup.Selection.Field) return
            val type = typer.typeOf(q).underlying()
            if (type is GoPointerType || type is GoInterfaceType) return
            escaping += v
        }
    }

    companion object {
        /** Bodies above this many nodes (generated code) are not analyzed. */
        const val MAX_NODES = 20_000

        private val EXIT_NAMES = setOf("Exit", "Goexit", "Fatal", "Fatalf", "Fatalln", "Panic", "Panicf", "Panicln", "FailNow", "SkipNow", "Skip", "Skipf")
        private val TESTING_EXITS = setOf("Fatal", "Fatalf", "FailNow", "SkipNow", "Skip", "Skipf")

        fun unparen(e: PsiElement?): GoExpression? {
            var x = e
            while (x is GoParenthesesExpr) x = x.inner
            return x as? GoExpression
        }
    }
}
