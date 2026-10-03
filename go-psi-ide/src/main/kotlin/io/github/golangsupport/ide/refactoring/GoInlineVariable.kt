package io.github.golangsupport.ide.refactoring

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.refactoring.GoInlineSupport.refuse
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * Inline Variable: a local `x := expr` / `var x [T] = expr` whose definition is the only value any read sees (no writes, no `&x`,
 * no pointer-method calls on it; the reaching definitions of the flow graph confirm it where the variable is tracked) has every read
 * replaced by `expr` and the declaration removed. Where evaluating `expr` at the use instead of at the declaration could change
 * the result (it calls something, receives, or reads fields, elements, pointers or package variables), only a single use in the same
 * block, reached with nothing that has effects in between, is accepted; a value that makes a new slice, map or pointer is never
 * duplicated.
 */
internal object GoInlineVariable {
    const val TITLE = "Inline Variable"

    private class Declaration(val statement: GoStatement, val defs: List<GoVarDefinition>, val values: List<GoExpression>, val type: PsiElement?, val spec: GoVarSpec?)

    fun plan(def: GoVarDefinition): List<GoTextEdit> {
        val name = def.name ?: refuse("The variable has no name")
        val owner = GoPsiUtil.functionOwner(def) ?: refuse("Only local variables can be inlined: '$name' is a package variable")
        val decl = declarationOf(def) ?: refuse("Only a variable declared by a statement with a value can be inlined")
        if (decl.values.isEmpty()) refuse("'$name' has no initial value")
        if (decl.values.size != decl.defs.size) refuse("The value of '$name' is not a separate expression")
        val value = decl.values[decl.defs.indexOf(def)]
        val service = GoSemanticService.getInstance(def.project)

        if (redeclared(def, decl.statement)) refuse("'$name' is redeclared by a later ':='")
        val uses = PsiTreeUtil.findChildrenOfType(owner, GoReferenceExpression::class.java)
            .filter { it.expression == null && it.identifier.text == name && service.resolve(it).contains(def) }
        if (uses.isEmpty()) refuse("'$name' is never used")
        for (use in uses) {
            if (GoInlineSupport.isWritten(use)) refuse("'$name' is written after its declaration")
            if (GoInlineSupport.isAddressTaken(use)) refuse("The address of '$name' is taken")
            if (GoInlineSupport.callsPointerMethod(use)) refuse("'$name' is the receiver of a pointer method that may change it")
        }

        val flow = GoControlFlow.enclosing(def)
        val reaching = flow?.let { GoReachingDefinitions.of(it) }
        flow?.accessesAt(def)?.firstOrNull { it.isWrite }?.let { defAccess -> checkReaching(name, flow, reaching, defAccess) }

        // what evaluating the value later would observe
        val effect = GoInlineSupport.hasSideEffects(value)
        val identity = GoInlineSupport.createsIdentity(value)
        val locals = ArrayList<GoReferenceExpression>()
        var memory = readsMemory(value)
        for (ref in GoInlineSupport.freeReferences(value)) {
            val target = service.resolve(ref).firstOrNull() as? GoNamedElement ?: continue
            if (target !is GoVarDefinition && target !is io.github.golangsupport.lang.psi.GoParamDefinition && target !is io.github.golangsupport.lang.psi.GoReceiver) continue
            val local = GoPsiUtil.functionOwner(target) != null
            if (!local) memory = true
            else if (flow == null || reaching == null || flow.variableOf(ref)?.let(flow::isTracked) != true) memory = true
            else locals += ref
        }
        if (effect && uses.size > 1) refuse("The value of '$name' has side effects and '$name' is used ${uses.size} times")
        if (identity && uses.size > 1) refuse("The value of '$name' makes a new object at each evaluation and '$name' is used ${uses.size} times")
        if (effect || memory) checkSingleUse(name, decl.statement, uses)
        for (use in uses) {
            if (locals.isNotEmpty() && inNestedLiteral(use, owner)) refuse("'$name' is used in a function literal: the variables of its value may change before it runs")
            GoInlineSupport.capturedName(GoInlineSupport.freeReferences(value), use)?.let { refuse("'$it' means something else where '$name' is used") }
            if (locals.isNotEmpty()) checkUnchanged(name, flow!!, reaching!!, locals, use)
        }

        val typed = decl.type != null && GoInlineSupport.needsConversion(service.typeOf(value), service.declarationType(def))
        val text = if (typed) GoInlineSupport.conversion(decl.type!!.text, value.text) else value.text
        val prec = if (typed) 7 else GoInlineSupport.precedence(value)
        val file = def.containingFile
        return uses.map { GoTextEdit(file, it.textRange, GoInlineSupport.placed(it, text, prec)) } + removal(def, decl, value, flow)
    }

    private fun declarationOf(def: GoVarDefinition): Declaration? {
        val (statement, decl) = when (val p = def.parent) {
            is GoShortVarDeclaration -> {
                // the parser makes a bare `x := v` the statement itself; a wrapping simple statement is the statement otherwise
                val statement = p.parent as? GoSimpleStatement ?: p
                statement to Declaration(statement, p.varDefinitionList, p.expressionList, null, null)
            }
            is GoVarSpec -> {
                val declaration = p.parent as? GoVarDeclaration ?: return null
                declaration to Declaration(declaration, p.varDefinitionList, p.expressionList, p.type, p)
            }
            else -> return null
        }
        return if (isStatementLevel(statement)) decl else null
    }

    fun isStatementLevel(statement: PsiElement): Boolean = statement.parent.let { it is GoBlock || it is GoExprCaseClause || it is GoTypeCaseClause || it is GoCommClause }

    /** A `:=` of the same name later in the same block assigns the variable instead of declaring a new one. */
    private fun redeclared(def: GoVarDefinition, statement: GoStatement): Boolean =
        statement.parent.children.filter { it is GoStatement && it.textRange.startOffset > statement.textRange.startOffset }
            .any { s -> GoPsiUtil.declarationsOf(s).any { it is GoVarDefinition && it.name == def.name && it.parent is GoShortVarDeclaration } }

    /** The definition is the only write of the variable and the only one reaching each read. */
    private fun checkReaching(name: String, flow: GoControlFlow, reaching: GoReachingDefinitions?, defAccess: GoFlowAccess) {
        val variable = defAccess.variable
        if (!flow.isTracked(variable)) return
        val accesses = flow.accessesOf(variable)
        if (accesses.any { it.isWrite && it != defAccess }) refuse("'$name' is written after its declaration")
        if (reaching == null) return
        for (read in accesses.filter { !it.isWrite }) {
            if (reaching.definitionsOf(read) != listOf(defAccess)) refuse("Not only the declared value of '$name' reaches its uses")
        }
    }

    /** Fields, elements, pointers: memory that writes through other names or calls may change. */
    private fun readsMemory(value: GoExpression): Boolean {
        val service = GoSemanticService.getInstance(value.project)
        val all = PsiTreeUtil.findChildrenOfType(value, PsiElement::class.java) + value
        return all.any { e ->
            when (e) {
                is GoIndexOrSliceExpr -> true
                is GoUnaryExpr -> e.mul != null
                is GoReferenceExpression -> e.expression?.let { q -> q !is GoReferenceExpression || service.resolve(q).none { it is GoImportSpec } } == true
                else -> false
            }
        }
    }

    /**
     * A value whose evaluation has or observes effects moves only to a single use evaluated once and unconditionally by a later statement
     * of the same block, with only effect-free declarations between and no call before the use within its statement.
     */
    private fun checkSingleUse(name: String, statement: GoStatement, uses: List<GoReferenceExpression>) {
        val use = uses.singleOrNull() ?: refuse("The value of '$name' may change between its ${uses.size} uses")
        val anchor = GoExtraction.anchorOf(use)
        if (anchor == null || anchor.parent !== statement.parent) refuse("'$name' is used where its value would be computed at another time (a nested block, loop, closure or condition)")
        for (s in statement.parent.children) {
            if (s !is GoStatement || s.textRange.startOffset <= statement.textRange.startOffset || s.textRange.startOffset >= anchor.textRange.startOffset) continue
            val declaration = s is GoVarDeclaration || s is GoConstDeclaration || s is GoTypeDeclaration || s is GoShortVarDeclaration || (s is GoSimpleStatement && s.statement is GoShortVarDeclaration)
            if (!declaration || GoInlineSupport.hasSideEffects(s)) refuse("Statements between the declaration of '$name' and its use may change its value")
        }
        val start = use.textRange.startOffset
        val earlier = PsiTreeUtil.findChildrenOfType(anchor, GoCallExpr::class.java).filter { it.textRange.endOffset <= start }
        if (earlier.any { GoInlineSupport.hasSideEffects(it) }) refuse("A call before the use of '$name' would run before its value is computed")
    }

    private fun inNestedLiteral(use: PsiElement, owner: PsiElement): Boolean = GoPsiUtil.functionOwner(use) !== owner || PsiTreeUtil.getParentOfType(use, GoFunctionLit::class.java)?.let { PsiTreeUtil.isAncestor(owner, it, true) } == true

    /** The local variables read by the value hold at [use] exactly the definitions they held at the declaration. */
    private fun checkUnchanged(name: String, flow: GoControlFlow, reaching: GoReachingDefinitions, locals: List<GoReferenceExpression>, use: GoReferenceExpression) {
        val node = flow.nodeOf(use) ?: refuse("'$name' is used where the flow of its value is not known")
        for (ref in locals) {
            val read = flow.accessesAt(ref).firstOrNull { !it.isWrite } ?: continue
            if (reaching.definitionsOf(read).toSet() != reaching.reaching(read.variable, node).toSet()) refuse("'${ref.identifier.text}' may change between the declaration of '$name' and its use")
        }
    }

    /** Removes the definition and its value, the spec of a group, or the whole statement. */
    private fun removal(def: GoVarDefinition, decl: Declaration, value: GoExpression, flow: GoControlFlow?): List<GoTextEdit> {
        val file = def.containingFile
        val text = file.viewProvider.contents
        if (decl.defs.size == 1) {
            val spec = decl.spec
            val declaration = spec?.parent as? GoVarDeclaration
            val target = if (declaration != null && declaration.varSpecList.size > 1) spec else decl.statement
            return listOf(GoTextEdit(file, GoInlineSupport.lineRange(text, target.textRange), ""))
        }
        if (decl.spec == null) {
            // `a, b := 1, 2` stays a declaration only while another name of it is new
            val others = decl.defs.filter { it != def }
            if (flow == null) refuse("Cannot tell whether the other names of the ':=' stay new")
            if (others.none { d -> d.name != "_" && flow.accessesAt(d).firstOrNull()?.variable == d }) refuse("The other names of the ':=' are not new: it would declare nothing")
        }
        return listOf(
            GoTextEdit(file, GoInlineSupport.listItemRange(def, decl.defs), ""),
            GoTextEdit(file, GoInlineSupport.listItemRange(value, decl.values), ""),
        )
    }
}

/**
 * Inline Constant: every use of a constant with an explicit value (`iota` and implicit repetition refused) becomes the value, as
 * `T(value)` when the constant has an explicit type the value does not already have; the declaration goes. Uses must be in the
 * constant's package (the value's names would need qualifying elsewhere).
 */
internal object GoInlineConstant {
    const val TITLE = "Inline Constant"

    fun plan(def: GoConstDefinition): List<GoTextEdit> {
        val name = def.name ?: refuse("The constant has no name")
        val spec = def.parent as? GoConstSpec ?: refuse("Not a constant declaration")
        val declaration = spec.parent as? GoConstDeclaration ?: refuse("Not a constant declaration")
        val defs = spec.constDefinitionList
        val values = spec.expressionList
        if (values.isEmpty()) refuse("'$name' repeats the value of the previous constant (implicit iota)")
        if (values.size != defs.size) refuse("The value of '$name' is not a separate expression")
        val value = values[defs.indexOf(def)]
        val refs = PsiTreeUtil.findChildrenOfType(value, GoReferenceExpression::class.java) + listOfNotNull(value as? GoReferenceExpression)
        if (refs.any { it.expression == null && it.identifier.text == "iota" }) refuse("The value of '$name' uses iota")
        val specs = declaration.constSpecList
        if (defs.size == 1 && specs.getOrNull(specs.indexOf(spec) + 1)?.expressionList?.isEmpty() == true) refuse("The next constant of the group repeats the value of '$name'")

        val service = GoSemanticService.getInstance(def.project)
        val owner = GoPsiUtil.functionOwner(def)
        val uses = if (owner != null) {
            PsiTreeUtil.findChildrenOfType(owner, GoReferenceExpression::class.java).filter { it.expression == null && it.identifier.text == name && service.resolve(it).contains(def) }
        } else {
            ReferencesSearch.search(def, GlobalSearchScope.projectScope(def.project)).findAll().mapNotNull { it.element as? GoReferenceExpression }
        }
        if (uses.isEmpty()) refuse("'$name' is never used")
        val home = def.containingFile as GoFile
        for (use in uses) {
            val file = use.containingFile as? GoFile ?: refuse("'$name' is used outside Go code")
            if (file.packageName != home.packageName || file.originalFile.virtualFile?.parent != home.originalFile.virtualFile?.parent) refuse("'$name' is used in another package")
            if (use.expression != null) refuse("'$name' is used qualified")
            GoInlineSupport.capturedName(GoInlineSupport.freeReferences(value), use)?.let { refuse("'$it' means something else where '$name' is used") }
        }

        val typeText = spec.type?.text
        val typed = typeText != null && GoInlineSupport.needsConversion(service.typeOf(value), service.declarationType(def))
        val text = if (typed) GoInlineSupport.conversion(typeText!!, value.text) else value.text
        val prec = if (typed) 7 else GoInlineSupport.precedence(value)
        val edits = uses.map { GoTextEdit(it.containingFile, it.textRange, GoInlineSupport.placed(it, text, prec)) }
        val file = def.containingFile
        val removal = if (defs.size == 1) {
            val target = if (specs.size > 1) spec else declaration
            listOf(GoTextEdit(file, GoInlineSupport.lineRange(file.viewProvider.contents, target.textRange), ""))
        } else {
            listOf(GoTextEdit(file, GoInlineSupport.listItemRange(def, defs), ""), GoTextEdit(file, GoInlineSupport.listItemRange(value, values), ""))
        }
        return edits + removal
    }
}
