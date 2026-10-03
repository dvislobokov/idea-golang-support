package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.PsiEquivalenceUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoZeroValues
import io.github.golangsupport.ide.rename.GoNamesValidator
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommCase
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTag
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType

/**
 * What Introduce Variable / Constant may extract and where the new declaration goes: the expression of a selection or around
 * the caret, the statement to insert before (null where evaluating the expression earlier would change the program), the
 * equivalent occurrences and the names.
 */
internal object GoExtraction {

    /** The expression covering exactly the selection [start, end) with the blanks around it trimmed, or null. */
    fun selectedExpression(file: GoFile, start: Int, end: Int): GoExpression? {
        val text = file.viewProvider.contents
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return null
        val first = file.findElementAt(s) ?: return null
        val last = file.findElementAt(e - 1) ?: return null
        var element: PsiElement? = PsiTreeUtil.findCommonParent(first, last)
        while (element != null && element !is PsiFile) {
            val range = element.textRange
            if (range.startOffset < s || range.endOffset > e) return null
            if (element is GoExpression && range.startOffset == s && range.endOffset == e) return element
            element = element.parent
        }
        return null
    }

    /** The expressions that contain the caret at [offset] (or end right before it), innermost first, up to the statement. */
    fun expressionsAt(file: GoFile, offset: Int): List<GoExpression> {
        val result = ArrayList<GoExpression>()
        for (at in listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)) {
            var e: PsiElement? = at
            while (e != null && e !is PsiFile && e !is GoStatement && e !is GoBlock) {
                if (e is GoExpression && result.none { it.textRange == e.textRange }) result += e
                e = e.parent
            }
            if (result.isNotEmpty()) break
        }
        return result.sortedBy { it.textLength }
    }

    /** Why [expr] cannot become a variable or constant regardless of where it stands, or null when it can. */
    fun rejectReason(expr: GoExpression): String? {
        val parent = expr.parent
        if (PsiTreeUtil.getParentOfType(expr, GoImportSpec::class.java, GoTag::class.java) != null) return "Not an expression of the code"
        if (parent is GoCallExpr && parent.expression === expr) return "Cannot extract the called function"
        if (isPackageOrType(expr)) return "Cannot extract a package qualifier or a type"
        if (parent is GoKey && expr is GoReferenceExpression &&
            GoSemanticService.getInstance(expr.project).resolve(expr).any { it is GoFieldDefinition }) return "Cannot extract a field name"
        if (parent is GoUnaryExpr && parent.and != null) return "Cannot extract the operand of '&': the address would change"
        if (isAssignmentTarget(expr)) return "Cannot extract the target of an assignment"
        return null
    }

    private fun isPackageOrType(expr: GoExpression): Boolean {
        val ref = expr as? GoReferenceExpression ?: return false
        return GoSemanticService.getInstance(expr.project).resolve(ref).any { it is GoImportSpec || it is GoTypeSpec }
    }

    /** Whether [expr] is (a part of) a left-hand side: `x`, `x.f` or `a[i]` of `x = …`, `x++`, `for x = range`; an index or an argument inside is fine. */
    private fun isAssignmentTarget(expr: GoExpression): Boolean {
        var child: PsiElement = expr
        var p: PsiElement? = expr.parent
        while (p != null && p !is GoStatement && p !is GoBlock && p !is PsiFile) {
            if (p is GoLeftHandExprList) return p.parent !is GoSimpleStatement || child === expr
            if (p is GoIndexOrSliceExpr && child !== p.expression) return false
            if (p is GoArgumentList || p is GoFunctionLit) return false
            child = p
            p = p.parent
        }
        return false
    }

    /**
     * The statement of a block or a case clause before which `name := expr` may go: the statement holding [expr] where the expression
     * is evaluated once and unconditionally when the statement starts. Null for the right operand of `&&`/`||`, a `for` condition or
     * post statement, an `else if` header, a `case` expression, the call of `go`/`defer`, and outside function bodies.
     */
    fun anchorOf(expr: GoExpression): GoStatement? {
        var child: PsiElement = expr
        var p: PsiElement? = expr.parent
        while (p != null && p !is PsiFile) {
            when (p) {
                is GoOrExpr, is GoAndExpr -> if ((p as io.github.golangsupport.lang.psi.GoBinaryExpr).right === child) return null
                is GoForClause -> if (GoPsiUtil.run { p.initStatement } !== child) return null
                is GoForStatement -> if (child is GoExpression) return null
                is GoExprCaseClause, is GoCommCase -> return null
                is GoIfStatement -> if (p.parent is GoElseStatement && child !is GoBlock && child !is GoElseStatement) return null
                is GoDeferStatement, is GoGoStatement -> if (child === expr) return null
                is GoFunctionLit, is GoFunctionOrMethodDeclaration -> return null
            }
            if (p is GoStatement && p.parent.let { it is GoBlock || it is GoExprCaseClause || it is GoTypeCaseClause || it is GoCommClause }) {
                return if (declaredWithin(expr, p.textRange.startOffset)) null else p
            }
            child = p
            p = p.parent
        }
        return null
    }

    /** Whether a name used in [expr] is declared in the same file between [from] and [expr] (an `if x := …;` header): it is not visible earlier. */
    fun declaredWithin(expr: GoExpression, from: Int): Boolean {
        val service = GoSemanticService.getInstance(expr.project)
        val start = expr.textRange.startOffset
        val refs = PsiTreeUtil.findChildrenOfType(expr, GoReferenceExpression::class.java) + listOfNotNull(expr as? GoReferenceExpression)
        return refs.any { ref ->
            service.resolve(ref).any { it.containingFile == expr.containingFile && it.textRange.startOffset in from until start }
        }
    }

    /** Expressions of [scope] equivalent to [expr] (the same text up to blanks and comments, the same declarations), [expr] included, in order. */
    fun occurrences(expr: GoExpression, scope: PsiElement, accept: (GoExpression) -> Boolean): List<GoExpression> {
        val result = ArrayList<GoExpression>()
        PsiTreeUtil.processElements(scope) { e ->
            if (e === expr || (e is GoExpression && e.javaClass == expr.javaClass && e.textLength >= 1 && accept(e) &&
                    PsiEquivalenceUtil.areElementsEquivalent(e, expr))) result += e as GoExpression
            true
        }
        // nested equivalents (`(a)` within `((a))`) cannot both be replaced: keep the outer
        return result.filter { e -> result.none { o -> o !== e && o.textRange.contains(e.textRange) } || e === expr }
            .sortedBy { it.textRange.startOffset }
    }

    /** The body or clause that holds all of [occurrences], innermost first, and its statement holding the first one. */
    fun commonAnchor(occurrences: List<GoExpression>): GoStatement? {
        val first = occurrences.first()
        val range = TextRange(first.textRange.startOffset, occurrences.maxOf { it.textRange.endOffset })
        var p: PsiElement? = first.parent
        while (p != null && p !is PsiFile && p !is GoFunctionLit && p !is GoFunctionOrMethodDeclaration) {
            if ((p is GoBlock || p is GoExprCaseClause || p is GoTypeCaseClause || p is GoCommClause) && p.textRange.contains(range)) {
                val statement = GoPsiUtil.statementIn(p, first) as? GoStatement ?: return null
                return if (occurrences.any { declaredWithin(it, statement.textRange.startOffset) }) null else statement
            }
            p = p.parent
        }
        return null
    }

    // --- names ---

    /** Names for a value of [type] computed by [expr]: `err`, `ctx`, the last word of a called or selected name, the type's; `v` last. */
    fun suggestNames(expr: GoExpression?, type: GoType?): List<String> {
        val names = LinkedHashSet<String>()
        if (type != null && GoZeroValues.isError(type)) names += "err"
        if (type != null && isContext(type)) names += "ctx"
        expr?.let { fromExpression(it) }?.let { names += fromWords(it) }
        type?.let { names += fromType(it) }
        names += "v"
        return names.filter { GoNamesValidator.isValidIdentifier(it) && !GoUniverse.isBuiltin(it) }
    }

    private fun isContext(type: GoType): Boolean =
        type is GoNamedType && type.name == "Context" && (type.declaration.containingFile as? GoFile)?.packageName == "context"

    private fun fromExpression(expr: GoExpression): String? = when (expr) {
        is GoParenthesesExpr -> PsiTreeUtil.getChildOfType(expr, GoExpression::class.java)?.let(::fromExpression)
        is GoReferenceExpression -> expr.identifier.text
        is GoCallExpr -> when (val callee = expr.expression) {
            is GoReferenceExpression -> {
                val name = callee.identifier.text
                when {
                    name == "len" || name == "cap" -> "n"
                    name in setOf("make", "new", "append", "min", "max") || isPackageOrType(callee) -> null
                    else -> stripVerb(name)
                }
            }
            else -> null
        }
        is GoUnaryExpr -> if (expr.and != null) expr.expression?.let(::fromExpression) else null
        is GoConversionExpr, is GoCompositeLit -> null
        else -> null
    }

    private fun stripVerb(name: String): String {
        for (prefix in listOf("Get", "get", "New", "new")) {
            if (name.length > prefix.length && name.startsWith(prefix) && name[prefix.length].isUpperCase()) return name.substring(prefix.length)
        }
        return name
    }

    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    /** `computeTotalPrice` -> `price`, `computeTotalPrice`; `URL` -> `url`. */
    fun fromWords(name: String): List<String> {
        val words = name.split(CAMEL).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val last = words.last().lowercase()
        val full = words.first().lowercase() + words.drop(1).joinToString("")
        return listOf(last, full).distinct()
    }

    private fun fromType(type: GoType): List<String> = when (type) {
        is GoPointerType -> fromType(type.elem)
        is GoNamedType -> if (type.declaration.containingFile.let { it is GoFile && it.packageName == "builtin" }) basicName(type.name) else fromWords(type.name)
        is GoBasicType -> basicName(type.kind.typeName)
        is GoSliceType, is GoArrayType -> {
            val elem = if (type is GoSliceType) type.elem else (type as GoArrayType).elem
            if (elem is GoBasicType && elem.kind == GoBasicKind.UINT8) listOf("data")
            else fromType(elem).firstOrNull()?.let { listOf(plural(it)) } ?: listOf("items")
        }
        is GoMapType -> listOf("m")
        is GoChanType -> listOf("ch")
        is GoSignatureType -> listOf("fn")
        is GoTupleType -> emptyList()
        else -> emptyList()
    }

    private fun basicName(name: String): List<String> = when {
        name == "string" || name == "untyped string" -> listOf("s")
        name == "bool" || name == "untyped bool" -> listOf("ok")
        name == "rune" || name == "untyped rune" -> listOf("r")
        name == "byte" -> listOf("b")
        name.startsWith("float") || name == "untyped float" -> listOf("f")
        name.contains("int") -> listOf("n")
        else -> emptyList()
    }

    private fun plural(word: String): String = when {
        word.endsWith("s") || word.endsWith("x") || word.endsWith("sh") || word.endsWith("ch") -> word + "es"
        word.endsWith("y") && word.length > 1 && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
        word.length == 1 -> word + "s"
        else -> word + "s"
    }

    /** [base], or `base1`, `base2`… the first one [taken] does not reject. */
    fun unique(base: String, taken: (String) -> Boolean): String {
        if (!taken(base)) return base
        var i = 1
        while (taken("$base$i")) i++
        return "$base$i"
    }

    /** Names a local declared at [anchor] in [owner] must avoid: those visible there, builtins, and every declaration of the function. */
    fun localTaken(anchor: PsiElement, owner: PsiElement?): (String) -> Boolean {
        val declared = HashSet<String>()
        if (owner != null) PsiTreeUtil.findChildrenOfType(owner, GoNamedElement::class.java).forEach { it.name?.let(declared::add) }
        return { name -> name in declared || GoUniverse.isBuiltin(name) || GoScopes.resolveName(anchor, name).isNotEmpty() }
    }

    /** Whether [type] is no value a variable can hold (`nil`, a statement call). */
    fun isValueless(type: GoType): Boolean =
        (type is GoBasicType && type.kind == GoBasicKind.UNTYPED_NIL) || (type is GoTupleType && type.types.isEmpty())
}
