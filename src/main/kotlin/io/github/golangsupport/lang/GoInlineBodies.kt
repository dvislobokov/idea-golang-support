package io.github.golangsupport.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSendStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * Sections F and G of the catalogue that [GoIdioms] does not cover: the next line after a statement (F6–F8, F11–F14, F18) and the first
 * line of a body (G1–G6, G8, G9), and the line after a struct type at package level (H1). The rest of F (`defer cancel()`, `defer f.Close()`, `defer mu.Unlock()`...) is [GoIdioms]'s, which is
 * asked first.
 */
object GoInlineBodies {
    fun nextLine(p: GoInlinePlace): String? {
        if (p.owner == null) return h1InterfaceAssertion(p)
        val statement = p.statement ?: return null
        if (statement.parent !is GoBlock) return null
        val previous = p.previousStatement
        val next = PsiTreeUtil.getNextSiblingOfType(statement, GoStatement::class.java)
        val first = previous == null
        val indent = p.blockIndent
        return (if (first && next == null) g1Constructor(p) ?: g2Getter(p) ?: g3Setter(p) ?: g6Unwrap(p) ?: GoInlineMethods.g5ErrorBody(p) ?: GoInlineMethods.g4EnumString(p) else null)
            ?: (if (first) f13Helper(p) ?: f7DoneInGoroutine(p) ?: GoInlineTests.f14Parallel(p) else null)
            ?: (if (first && next == null) GoInlineTests.g8TestBody(p) ?: GoInlineTests.g9BenchmarkBody(p) else null)
            ?: previous?.let { f6GoroutineAfterAdd(p, it, indent) ?: f8Wait(p, it, next) ?: f12ServerClose(p, it, next) ?: f11CloseAfterProducer(p, it, next) ?: f18BuilderString(p, it, next) }
    }

    // --- WaitGroup ---

    private fun isWaitGroup(p: GoInlinePlace, variable: GoInlineVariable): Boolean =
        p.standard(variable)?.let { it.first == "sync" && it.second == "WaitGroup" } == true

    /** `X.Add(…)` / `X.Done()` called on a WaitGroup in scope: X. */
    private fun waitGroupCall(p: GoInlinePlace, call: GoCallExpr, method: String): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier?.text != method) return null
        val receiver = callee.expression?.text ?: return null
        val root = receiver.substringBefore('.')
        val variable = p.variables.firstOrNull { it.name == root } ?: return null
        return receiver.takeIf { root != receiver || isWaitGroup(p, variable) }
    }

    /** After `wg.Add(1)`: `go func() {` with `defer wg.Done()` first in it. */
    fun f6GoroutineAfterAdd(p: GoInlinePlace, previous: GoStatement, indent: String): String? {
        val call = PsiTreeUtil.findChildOfType(previous, GoCallExpr::class.java)?.takeIf { it.textRange.endOffset == previous.textRange.endOffset } ?: return null
        val group = waitGroupCall(p, call, "Add") ?: return null
        if (PsiTreeUtil.getParentOfType(previous, GoForStatement::class.java) == null && previous.parent?.parent is GoFunctionLit) return null
        return "go func() {\n$indent${p.unit}defer $group.Done()\n$indent${p.unit}\n$indent}()"
    }

    /** The first line of `go func() {` with a WaitGroup in scope: `defer wg.Done()`. */
    fun f7DoneInGoroutine(p: GoInlinePlace): String? {
        val literal = p.owner as? GoFunctionLit ?: return null
        if (PsiTreeUtil.getParentOfType(literal, GoGoStatement::class.java)?.let { PsiTreeUtil.isAncestor(it.expression, literal, false) } != true) return null
        val groups = p.variables.filter { isWaitGroup(p, it) }
        return groups.singleOrNull()?.let { "defer ${it.name}.Done()" }
    }

    /** After the loop that starts goroutines counted by a WaitGroup: `wg.Wait()`. */
    fun f8Wait(p: GoInlinePlace, previous: GoStatement, next: GoStatement?): String? {
        val loop = previous as? GoForStatement ?: PsiTreeUtil.getChildOfType(previous, GoForStatement::class.java) ?: return null
        if (PsiTreeUtil.findChildOfType(loop, GoGoStatement::class.java) == null) return null
        val calls = PsiTreeUtil.findChildrenOfType(loop, GoCallExpr::class.java)
        val group = calls.mapNotNull { waitGroupCall(p, it, "Add") ?: waitGroupCall(p, it, "Done") }.distinct().singleOrNull()
            ?: p.variables.filter { isWaitGroup(p, it) }.singleOrNull()?.name ?: return null
        if (next?.text?.contains("$group.Wait()") == true) return null
        return "$group.Wait()"
    }

    /** The loop of [previous] (the statement itself or the loop it labels). */
    private fun loopOf(previous: GoStatement): GoForStatement? = previous as? GoForStatement ?: PsiTreeUtil.getChildOfType(previous, GoForStatement::class.java)

    /** After the loop of a goroutine that sends to a channel made outside it: `close(ch)`, the producer is the one to close it. */
    fun f11CloseAfterProducer(p: GoInlinePlace, previous: GoStatement, next: GoStatement?): String? {
        if (next != null) return null
        val literal = p.owner as? GoFunctionLit ?: return null
        if (PsiTreeUtil.getParentOfType(literal, GoGoStatement::class.java)?.let { PsiTreeUtil.isAncestor(it.expression, literal, false) } != true) return null
        val loop = loopOf(previous) ?: return null
        val sends = PsiTreeUtil.findChildrenOfType(loop, GoSendStatement::class.java).filter { GoPsiUtil.functionOwner(it) == literal }
        val channel = sends.mapNotNull { it.leftHandExprList?.expressionList?.singleOrNull() as? GoReferenceExpression }.map { it.text }.distinct().singleOrNull() ?: return null
        val variable = p.variables.firstOrNull { it.name == channel } ?: return null
        // made outside the goroutine, and not closed in it yet
        if (PsiTreeUtil.isAncestor(literal, variable.element, false) || Regex("""close\(\s*$channel\s*\)""").containsMatchIn(literal.text)) return null
        if ((variable.type.underlying() as? io.github.golangsupport.semantic.types.GoChanType)?.dir == io.github.golangsupport.semantic.types.GoChanDir.RECV) return null
        return "close($channel)"
    }

    /** After a loop that writes to a `strings.Builder`, at the end of a function returning a string: `return sb.String()`. */
    fun f18BuilderString(p: GoInlinePlace, previous: GoStatement, next: GoStatement?): String? {
        if (next != null || p.statement?.parent !== p.body) return null
        val results = (p.typeOf(p.owner as? io.github.golangsupport.lang.psi.GoNamedElement ?: return null) as? GoSignatureType)?.results ?: return null
        if (results.size != 1 || (results[0].type.underlying() as? io.github.golangsupport.semantic.types.GoBasicType)?.kind?.isString != true) return null
        val loop = loopOf(previous) ?: return null
        val written = PsiTreeUtil.findChildrenOfType(loop, GoCallExpr::class.java).mapNotNull { call ->
            val callee = call.expression as? GoReferenceExpression ?: return@mapNotNull null
            if (callee.identifier?.text !in setOf("WriteString", "WriteByte", "WriteRune", "Write")) return@mapNotNull null
            callee.expression?.text
        }.distinct()
        val builder = written.singleOrNull()?.let { name -> p.variables.firstOrNull { it.name == name } } ?: return null
        if (!p.isStandard(builder, "strings", "Builder")) return null
        return "return ${builder.name}.String()"
    }

    /**
     * After `type T struct { … }` at package level, when `T` (through a pointer) implements one interface of the package: the check
     * the compiler makes of it, `var _ I = (*T)(nil)`.
     */
    fun h1InterfaceAssertion(p: GoInlinePlace): String? {
        if (p.slot.typed.isNotEmpty() && !"var".startsWith(p.slot.typed)) return null
        val before = p.text.subSequence(0, p.slot.start).toString().trimEnd()
        val declaration = p.file.children.filterIsInstance<GoTypeDeclaration>().lastOrNull { it.textRange.endOffset <= p.slot.start } ?: return null
        if (declaration.textRange.endOffset !in before.length..p.slot.start) return null
        val spec = declaration.typeSpecList.singleOrNull() ?: return null
        if (spec.type !is io.github.golangsupport.lang.psi.GoStructType || spec.typeParameters != null) return null
        val name = spec.name ?: return null
        val type = p.typeOf(p.packageTypes[name] ?: return null) as? GoNamedType ?: return null
        val interfaces = p.packageTypes.values.filter { candidate ->
            val iface = (p.typeOf(candidate) as? GoNamedType)?.underlying() as? GoInterfaceType ?: return@filter false
            iface.methods.isNotEmpty() && runCatching { p.semantic.implements(GoPointerType(type), iface) }.getOrDefault(false)
        }.mapNotNull { it.name }
        val iface = interfaces.singleOrNull() ?: return null
        if (Regex("""var\s+_\s+$iface\s*=\s*\(\*$name\)""").containsMatchIn(p.text)) return null
        return "var _ $iface = (*$name)(nil)"
    }

    private val TEST_SERVER = Regex("""^httptest\.New(TLS)?Server\(""")

    /** After `srv := httptest.NewServer(…)`: `defer srv.Close()`. */
    fun f12ServerClose(p: GoInlinePlace, previous: GoStatement, next: GoStatement?): String? {
        val short = previous as? GoShortVarDeclaration ?: PsiTreeUtil.getChildOfType(previous, GoShortVarDeclaration::class.java) ?: return null
        val name = short.varDefinitionList.singleOrNull()?.name ?: return null
        val value = short.expressionList.singleOrNull() as? GoCallExpr ?: return null
        if (!TEST_SERVER.containsMatchIn(value.text)) return null
        if (next is GoDeferStatement && next.text.contains("$name.Close()")) return null
        return "defer $name.Close()"
    }

    private val TEST_FUNCTIONS = Regex("""^(Test|Benchmark|Fuzz|Example)""")

    /** The first line of a helper of tests, `func assertUser(t *testing.T, ...)`: `t.Helper()`. */
    fun f13Helper(p: GoInlinePlace): String? {
        val function = p.owner as? GoFunctionDeclaration ?: return null
        if (TEST_FUNCTIONS.containsMatchIn(function.name ?: return null)) return null
        if (function.block?.text?.contains(".Helper()") == true) return null
        val t = p.variables.filter { it.isParameter }.firstOrNull { v ->
            p.standard(v)?.let { it.first == "testing" && (it.third && it.second in setOf("T", "B", "F") || !it.third && it.second == "TB") } == true
        } ?: return null
        return "${t.name}.Helper()"
    }

    // --- bodies of declarations (G) ---

    /** `func NewT(a A, b B) *T {`: `return &T{a: a, b: b}`. */
    fun g1Constructor(p: GoInlinePlace): String? {
        val function = p.owner as? GoFunctionDeclaration ?: return null
        val signature = p.typeOf(function) as? GoSignatureType ?: return null
        return GoInlineValues.constructorLiteral(p, function, signature.results.map { it.type })?.let { "return $it" }
    }

    private fun receiverFields(p: GoInlinePlace): Pair<String, List<io.github.golangsupport.semantic.types.GoField>>? {
        val method = p.owner as? GoMethodDeclaration ?: return null
        val receiver = method.receiver ?: return null
        val name = receiver.name?.takeIf { it != "_" } ?: return null
        val struct = p.structOf(p.typeOf(receiver)) ?: return null
        return name to struct.fields.filter { !it.embedded }
    }

    /** A getter `func (u *User) Name() string {`: `return u.name`. */
    fun g2Getter(p: GoInlinePlace): String? {
        val method = p.owner as? GoMethodDeclaration ?: return null
        val signature = p.typeOf(method) as? GoSignatureType ?: return null
        if (signature.params.isNotEmpty() || signature.results.size != 1) return null
        val (receiver, fields) = receiverFields(p) ?: return null
        val name = method.name ?: return null
        val wanted = setOf(name, name.removePrefix("Get")).map { it.lowercase() }
        val field = fields.filter { it.name.lowercase() in wanted && GoTypePredicates.identical(it.type, signature.results[0].type) }.singleOrNull() ?: return null
        if (field.name == name) return null
        return "return $receiver.${field.name}"
    }

    /** A setter `func (u *User) SetName(name string) {`: `u.name = name`. */
    fun g3Setter(p: GoInlinePlace): String? {
        val method = p.owner as? GoMethodDeclaration ?: return null
        val name = method.name?.takeIf { it.startsWith("Set") && it.length > 3 } ?: return null
        val signature = p.typeOf(method) as? GoSignatureType ?: return null
        val parameter = signature.params.singleOrNull()?.takeIf { signature.results.isEmpty() } ?: return null
        val parameterName = parameter.name?.takeIf { it.isNotEmpty() && it != "_" } ?: return null
        val (receiver, fields) = receiverFields(p) ?: return null
        val field = fields.filter { it.name.equals(name.removePrefix("Set"), ignoreCase = true) && GoTypePredicates.assignable(parameter.type, it.type) }.singleOrNull() ?: return null
        return "$receiver.${field.name} = $parameterName"
    }

    /** `func (e *MyErr) Unwrap() error {`: `return e.err`. */
    fun g6Unwrap(p: GoInlinePlace): String? {
        val method = p.owner as? GoMethodDeclaration ?: return null
        if (method.name != "Unwrap") return null
        val (receiver, fields) = receiverFields(p) ?: return null
        val field = fields.filter { GoReturnValues.isError(it.type) }.singleOrNull() ?: return null
        return "return $receiver.${field.name}"
    }
}
