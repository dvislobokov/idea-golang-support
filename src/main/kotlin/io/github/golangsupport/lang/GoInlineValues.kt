package io.github.golangsupport.lang

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoValue
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Sections B–E of the catalogue: the values of `return`, the arguments of a call, the fields of a composite literal, what a `range`
 * goes over and the condition of an `if`. Each rule is a function named by its id.
 */
object GoInlineValues {
    private val OK_NAMES = setOf("ok", "found", "exists", "has", "present")
    private val ERROR_NAME = Regex("""^\w*[eE]rr\w*$""")

    // --- candidates for a value of a type ---

    /** Whether a variable of [type] may be passed where [wanted] is expected, sure enough to suggest it. */
    fun fits(p: GoInlinePlace, type: GoType, wanted: GoType): Boolean {
        if (type is GoUnknownType || wanted is GoUnknownType || p.isEmptyInterface(wanted)) return false
        if (type is GoBasicType && type.isUntyped) return false
        return if (wanted.underlying() is GoInterfaceType) GoTypePredicates.identical(type, wanted) || runCatching { GoTypePredicates.assignable(type, wanted) }.getOrDefault(false)
        else GoTypePredicates.identical(type, wanted) || runCatching { GoTypePredicates.assignable(type, wanted) }.getOrDefault(false) && type !is GoBasicType
    }

    /**
     * The variable for a parameter [name] of [type]: the context for a context, else the only variable in scope that fits the type,
     * else the one whose name fits the parameter best (C1–C3). With [fromCases], a field of the case of a table test (`tt.input`) first.
     */
    fun candidate(p: GoInlinePlace, name: String?, type: GoType, exclude: Set<String> = emptySet(), fromCases: Boolean = false): String? {
        if (fromCases && name != null) caseField(p, name, type)?.let { return it }
        if (p.isStandard(type, "context", "Context")) {
            val contexts = p.variables.filter { it.name !in exclude && (p.isStandard(it.type, "context", "Context") || p.isStandard(it, "context", "Context")) }
            return (contexts.singleOrNull() ?: contexts.firstOrNull { it.name == "ctx" })?.name
        }
        val fitting = p.variables.filter { it.name !in exclude && fits(p, it.type, type) }
        fitting.singleOrNull()?.let { return it.name }
        if (name == null || fitting.isEmpty()) return null
        val scored = fitting.map { it to GoInlineSuggestions.nameScore(it.name, name) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first?.name
    }

    /** `tt.input` for a parameter `input`: the range variable of the loop over the cases of a table test. */
    fun caseField(p: GoInlinePlace, name: String, type: GoType): String? {
        val loop = PsiTreeUtil.getParentOfType(p.leaf, GoForStatement::class.java) ?: return null
        val case = loop.rangeClause?.varDefinitionList?.lastOrNull() ?: return null
        val struct = p.structOf(p.typeOf(case)) ?: return null
        val direct = struct.fields.filter { it.name.equals(name, ignoreCase = true) && fits(p, it.type, type) }.map { "${case.name}.${it.name}" }
        direct.singleOrNull()?.let { return it }
        // `tt.args.name`, the way the generated table tests have it
        val nested = struct.fields.flatMap { outer ->
            p.structOf(outer.type)?.fields.orEmpty().filter { it.name.equals(name, ignoreCase = true) && fits(p, it.type, type) }.map { "${case.name}.${outer.name}.${it.name}" }
        }
        return nested.singleOrNull()
    }

    /** All the arguments of [signature] (but the variadic one), each sure and each a different variable; null when one is not. */
    fun fill(p: GoInlinePlace, signature: GoSignatureType, exclude: Set<String> = emptySet(), fromCases: Boolean = false): String? {
        val parameters = if (signature.variadic) signature.params.dropLast(1) else signature.params
        val used = HashSet(exclude)
        val values = parameters.map { parameter ->
            val value = candidate(p, parameter.name, parameter.type, used, fromCases) ?: return null
            if (!used.add(value)) return null
            value
        }
        return values.joinToString(", ")
    }

    // --- return (B) ---

    fun returnValues(p: GoInlinePlace): String? {
        val statement = PsiTreeUtil.getParentOfType(p.leaf, GoReturnStatement::class.java) ?: return null
        if (statement.expressionList.size != 1) return null
        val results = runCatching { p.semantic.enclosingResultTypes(statement) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return b12ContextError(p, statement) ?: b2WrappedError(p, statement, results) ?: b1ErrorValues(p, statement, results)
            ?: b3BuiltValues(p, results) ?: b11AccumulatedSlice(p, results) ?: b4ConstructorLiteral(p, results)
            ?: GoInlineMethods.b6ErrorMessage(p) ?: GoInlineMethods.b9Len(p) ?: b13JoinedErrors(p, results) ?: b7OppositeBool(p, statement, results)
    }

    /** The error checked by the `if X != nil {` whose block holds [statement] directly. */
    private fun checkedError(statement: GoReturnStatement): Pair<String, GoIfStatement>? {
        val block = statement.parent as? GoBlock ?: return null
        val check = block.parent as? GoIfStatement ?: return null
        val condition = GoPsiUtil.run { check.condition } as? GoBinaryExpr ?: return null
        if (condition.right?.text != "nil" || !condition.text.contains("!=")) return null
        val error = (condition.left as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text ?: return null
        return if (ERROR_NAME.matches(error)) error to check else null
    }

    private fun plainErrorValues(p: GoInlinePlace, statement: GoReturnStatement, results: List<GoType>, error: String): String? {
        if (results.size == 1) return if (GoReturnValues.isError(results[0])) error else null
        return GoReturnValues.forReturn(p.leaf)?.plain?.takeIf { it.isNotEmpty() && it.endsWith(error) }
    }

    /** Inside `if err != nil {`: the zero values and the error, `nil, err` (what [GoReturnValues] gives the completion list too). */
    fun b1ErrorValues(p: GoInlinePlace, statement: GoReturnStatement, results: List<GoType>): String? {
        val (error, _) = checkedError(statement) ?: return null
        return plainErrorValues(p, statement, results, error)
    }

    /** The same with the error wrapped, where the file or the function wraps its errors: `nil, fmt.Errorf("get user: %w", err)`. */
    fun b2WrappedError(p: GoInlinePlace, statement: GoReturnStatement, results: List<GoType>): String? {
        val (error, check) = checkedError(statement) ?: return null
        val owner = p.owner ?: return null
        if (!GoIdioms.wrapsErrors(p.text) && !WRAPPED.containsMatchIn(owner.text)) return null
        val plain = plainErrorValues(p, statement, results, error) ?: return null
        val failed = (GoPsiUtil.run { check.initStatement } ?: PsiTreeUtil.getPrevSiblingOfType(check, GoStatement::class.java))?.text?.lineSequence()?.firstOrNull()?.trim()
        val message = GoIdioms.failure(failed) ?: (owner as? GoFunctionOrMethodDeclaration)?.name?.let { GoInlineSuggestions.words(it) } ?: return null
        return plain.dropLast(error.length) + "${p.qualifier("fmt")}.Errorf(\"$message: %w\", $error)"
    }

    private val WRAPPED = Regex("""fmt\.Errorf\("[^"\n]*%w""")

    /** A local of the function (not a parameter) of exactly [type], when there is one only. */
    private fun builtLocal(p: GoInlinePlace, type: GoType): String? {
        if (type is GoUnknownType) return null
        return p.variables.filter { !it.isParameter && GoTypePredicates.identical(it.type, type) && p.body?.textRange?.contains(it.element.textRange) == true }
            .singleOrNull()?.name
    }

    /** At the end of `func f() (*T, error)` with a `t` built above: `t, nil`. */
    fun b3BuiltValues(p: GoInlinePlace, results: List<GoType>): String? {
        if (results.size < 2 || !GoReturnValues.isError(results.last())) return null
        val values = results.dropLast(1).map { builtLocal(p, it) ?: return null }
        return values.joinToString(", ") + ", nil"
    }

    /** `func f() []T` with the slice accumulated above: `out`. */
    fun b11AccumulatedSlice(p: GoInlinePlace, results: List<GoType>): String? {
        val result = results.singleOrNull()?.takeIf { it.underlying() is GoSliceType } ?: return null
        return builtLocal(p, result)
    }

    /** In `NewT(a, b)` returning `*T`: `&T{a: a, b: b}`, the parameters that have a field of their name (`, nil` when it returns an error). */
    fun b4ConstructorLiteral(p: GoInlinePlace, results: List<GoType>): String? {
        val function = p.owner as? GoFunctionDeclaration ?: return null
        return constructorLiteral(p, function, results)
    }

    fun constructorLiteral(p: GoInlinePlace, function: GoFunctionDeclaration, results: List<GoType>): String? {
        val name = function.name ?: return null
        if (!name.startsWith("New") || results.isEmpty() || results.size > 2 || results.size == 2 && !GoReturnValues.isError(results[1])) return null
        val result = results[0]
        val pointer = result is GoPointerType
        val named = (if (result is GoPointerType) result.elem else result) as? GoNamedType ?: return null
        val struct = named.underlying() as? GoStructType ?: return null
        if (builtLocal(p, result) != null) return null
        val signature = p.typeOf(function) as? GoSignatureType ?: return null
        val pairs = signature.params.mapNotNull { parameter ->
            val parameterName = parameter.name?.takeIf { it.isNotEmpty() && it != "_" } ?: return@mapNotNull null
            val field = struct.fields.filter { !it.embedded && it.name.equals(parameterName, ignoreCase = true) && GoTypePredicates.assignable(parameter.type, it.type) }.singleOrNull()
            field?.let { "${it.name}: $parameterName" }
        }
        if (pairs.isEmpty()) return null
        val literal = (if (pointer) "&" else "") + (p.typeText(named) ?: return null) + "{" + pairs.joinToString(", ") + "}"
        return if (results.size == 2) "$literal, nil" else literal
    }

    /** In the `case <-ctx.Done():` of a `select`: `ctx.Err()`, with the zero values before it. */
    fun b12ContextError(p: GoInlinePlace, statement: GoReturnStatement): String? {
        val clause = statement.parent as? GoCommClause ?: return null
        val context = Regex("""^case\s*<-\s*(\w+)\.Done\(\)""").find(clause.commCase?.text.orEmpty())?.groupValues?.get(1) ?: return null
        val function = GoReturnValues.function(p.leaf) ?: return null
        if (function.results.isEmpty()) return null
        return GoIdioms.returnStatement("$context.Err()", function).removePrefix("return ").takeIf { it != "return" }
    }

    /** `func f() error` with the errors gathered in an `errs []error` in scope: `errors.Join(errs...)`. */
    fun b13JoinedErrors(p: GoInlinePlace, results: List<GoType>): String? {
        if (results.size != 1 || !GoReturnValues.isError(results[0])) return null
        val lists = p.variables.filter { (it.type.underlying() as? GoSliceType)?.elem?.let(GoReturnValues::isError) == true }
        return lists.singleOrNull()?.let { "${p.qualifier("errors")}.Join(${it.name}...)" }
    }

    /**
     * `func f() bool`: in an `if` that leaves early, the opposite of the `return true|false` the function ends with; at the end, the
     * opposite of what the early returns give (when they all give the same).
     */
    fun b7OppositeBool(p: GoInlinePlace, statement: GoReturnStatement, results: List<GoType>): String? {
        val result = results.singleOrNull() ?: return null
        if ((result.underlying() as? GoBasicType)?.kind?.isBoolean != true) return null
        val body = p.body ?: return null
        val others = PsiTreeUtil.findChildrenOfType(body, GoReturnStatement::class.java).filter { it != statement && GoPsiUtil.functionOwner(it) == p.owner }
        val literal = { r: GoReturnStatement -> r.expressionList.singleOrNull()?.text?.takeIf { it == "true" || it == "false" } }
        val opposite = { v: String -> if (v == "true") "false" else "true" }
        return if (statement.parent === body) {
            // the end: the early returns inside the `if`s
            val early = others.filter { it.textRange.startOffset < statement.textRange.startOffset }.map(literal)
            early.distinct().singleOrNull()?.let(opposite)
        } else {
            val inIf = (statement.parent as? GoBlock)?.parent is GoIfStatement
            val last = body.statementList.lastOrNull() as? GoReturnStatement
            if (!inIf || last == null || last == statement) null else literal(last)?.let(opposite)
        }
    }

    // --- call arguments (C) ---

    fun argument(p: GoInlinePlace): String? {
        val expression = p.expression ?: return null
        val list = expression.parent as? GoArgumentList ?: return null
        val call = list.parent as? GoCallExpr ?: return null
        val arguments = call.arguments
        val index = arguments.indexOf(expression)
        if (index < 0) return null
        c13TestRun(p, call, index)?.let { return it }
        c11MakeLength(p, call, index)?.let { return it }
        GoInlineTests.c14AssertEqual(p, call, index)?.let { return it }
        c12AppendedElement(p, call, index)?.let { return it }
        val known = runCatching { p.semantic.calleeSignature(call) }.getOrNull()
        GoInlineLibrary.c17NotifyContext(p, call, index, known)?.let { return it }
        GoInlineMethods.c18SortSlice(p, call, index)?.let { return it }
        GoInlineMethods.c19SortFunc(p, call, index)?.let { return it }
        c9ErrorsIs(p, call, index)?.let { return it }
        val signature = known ?: return null
        return c8FormatArguments(p, call, signature, index) ?: c6Pointer(p, call, signature, index) ?: c5Spread(p, signature, index, arguments.size)
            ?: c4AllArguments(p, signature, index, arguments.size) ?: c1Context(p, signature, index) ?: c2c3ByType(p, signature, index, arguments)
    }

    private fun parameterAt(signature: GoSignatureType, index: Int) = if (signature.variadic && index >= signature.params.size - 1) null else signature.params.getOrNull(index)

    /** The parameter `ctx context.Context`: the context in scope. */
    fun c1Context(p: GoInlinePlace, signature: GoSignatureType, index: Int): String? {
        val parameter = parameterAt(signature, index) ?: return null
        if (!p.isStandard(parameter.type, "context", "Context")) return null
        return candidate(p, parameter.name, parameter.type)
    }

    /** The variable of the type of the parameter: the only one (C2), or the one whose name fits the parameter (C3). */
    fun c2c3ByType(p: GoInlinePlace, signature: GoSignatureType, index: Int, arguments: List<com.intellij.psi.PsiElement>): String? {
        val parameter = parameterAt(signature, index) ?: return null
        val written = arguments.filterIndexed { i, _ -> i != index }.map { it.text }.toSet()
        return candidate(p, parameter.name, parameter.type, exclude = written)
    }

    /** At the first argument of a call with nothing written yet: all of them, when each is sure (C4). */
    fun c4AllArguments(p: GoInlinePlace, signature: GoSignatureType, index: Int, count: Int): String? {
        val fixed = if (signature.variadic) signature.params.size - 1 else signature.params.size
        if (index != 0 || count != 1 || fixed < 2) return null
        return fill(p, signature)
    }

    /** The variadic parameter `...T` with a `[]T` in scope: `items...`. */
    fun c5Spread(p: GoInlinePlace, signature: GoSignatureType, index: Int, count: Int): String? {
        if (!signature.variadic || index != signature.params.size - 1 || count != index + 1) return null
        val type = signature.params.last().type
        val slices = p.variables.filter { GoTypePredicates.identical(it.type, type) }
        return slices.singleOrNull()?.let { "${it.name}..." }
    }

    private val DECODERS = setOf("Unmarshal", "Decode", "Scan", "DecodeElement", "UnmarshalJSON", "Bind", "ShouldBind", "ShouldBindJSON", "BindJSON")

    /** `json.Unmarshal(data, |`: `&v` of the `var v T` declared above. */
    fun c6Pointer(p: GoInlinePlace, call: GoCallExpr, signature: GoSignatureType, index: Int): String? {
        val callee = (call.expression as? GoReferenceExpression)?.identifier?.text ?: return null
        if (callee !in DECODERS) return null
        val parameter = signature.params.getOrNull(index) ?: signature.params.lastOrNull()?.takeIf { signature.variadic } ?: return null
        val type = parameter.type.let { if (signature.variadic && parameter === signature.params.last()) (it.underlying() as? GoSliceType)?.elem ?: it else it }
        if (!p.isEmptyInterface(type)) return null
        val declared = p.variables.filter { v ->
            val spec = v.element.parent as? GoVarSpec ?: return@filter false
            spec.expressionList.isEmpty() && spec.type != null && v.type !is GoUnknownType && v.type.underlying() !is GoInterfaceType && v.type !is GoPointerType &&
                p.body?.textRange?.contains(v.element.textRange) == true
        }
        return declared.singleOrNull()?.let { "&${it.name}" }
    }

    private val VERB = Regex("""%[-+# 0]*\d*(?:\.\d+)?([a-zA-Z%])""")

    /** `fmt.Printf("%s has %d items", |`: the variables of the types of the verbs, in order. */
    fun c8FormatArguments(p: GoInlinePlace, call: GoCallExpr, signature: GoSignatureType, index: Int): String? {
        if (!signature.variadic || signature.params.size < 2) return null
        val formatIndex = signature.params.size - 2
        val format = signature.params[formatIndex]
        if (format.name != "format" || (format.type.underlying() as? GoBasicType)?.kind?.isString != true) return null
        val literal = call.arguments.getOrNull(formatIndex) as? GoStringLiteral ?: return null
        val text = literal.text
        val verbs = VERB.findAll(text).filter { it.groupValues[1] != "%" }.toList()
        val already = index - formatIndex - 1
        if (already < 0 || already >= verbs.size || call.arguments.size != index + 1) return null
        val written = call.arguments.drop(formatIndex + 1).dropLast(1).map { it.text }.toMutableSet()
        val values = verbs.drop(already).map { verb ->
            val word = Regex("""(\w+)\W*$""").find(text.substring(0, verb.range.first))?.groupValues?.get(1)
            val fitting = p.variables.filter { it.name !in written && verbFits(p, verb.groupValues[1][0], it.type) }
            val chosen = fitting.singleOrNull()?.takeIf { verb.groupValues[1] != "v" }
                ?: word?.let { w -> fitting.filter { GoInlineSuggestions.nameScore(it.name, w) > 0 }.singleOrNull() } ?: return null
            written += chosen.name
            chosen.name
        }
        return values.joinToString(", ")
    }

    private fun verbFits(p: GoInlinePlace, verb: Char, type: GoType): Boolean {
        if (type is GoUnknownType) return false
        val basic = type.underlying() as? GoBasicType
        return when (verb) {
            's', 'q' -> basic?.kind?.isString == true
            'd', 'c', 'o', 'b' -> basic?.kind?.isInteger == true
            'x', 'X' -> basic?.kind?.isInteger == true || basic?.kind?.isString == true
            'f', 'g', 'e', 'F', 'G', 'E' -> basic?.kind?.isFloat == true
            't' -> basic?.kind?.isBoolean == true
            'w' -> GoReturnValues.isError(type)
            'v', 'T' -> true
            'p' -> type is GoPointerType
            else -> false
        }
    }

    /** `make([]T, |`: `0, len(src)` or `len(src)`, by how the slice made is used below (A1–A2). */
    fun c11MakeLength(p: GoInlinePlace, call: GoCallExpr, index: Int): String? {
        val callee = (call.expression as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text
        if (callee != "make" || index != 1 || call.arguments.size != 2) return null
        val sliceText = call.arguments[0].text
        if (!sliceText.startsWith("[]")) return null
        val elementText = sliceText.removePrefix("[]")
        val declaration = PsiTreeUtil.getParentOfType(call, GoShortVarDeclaration::class.java)
        val name = declaration?.varDefinitionList?.singleOrNull()?.name
        val uses = name?.let { p.definitionOf(it) }?.let(p::usesOf) ?: GoInlineUses()
        val ranged = (uses.appended.map { it.second } + uses.indexAssigned.map { it.second }).filterNotNull().map { it.text }.distinct()
        val collection = ranged.singleOrNull()
            ?: p.variables.filter { v -> v.name != name && p.elementOf(v.type)?.let { p.typeText(it) } == elementText }.singleOrNull()?.name ?: return null
        val byPosition = uses.indexAssigned.isNotEmpty() && uses.appended.isEmpty()
        return if (byPosition) "len($collection)" else "0, len($collection)"
    }

    /** `errors.Is(err, |`: the sentinel error of the package the function mentions, or the only one. */
    fun c9ErrorsIs(p: GoInlinePlace, call: GoCallExpr, index: Int): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier?.text != "Is" || index != 1 || call.arguments.size != 2) return null
        if (callee.expression?.text != (GoReturnValues.importName(p.original, "errors") ?: return null)) return null
        val error = (call.arguments[0] as? GoReferenceExpression)?.text ?: return null
        return GoInlineFlow.e9ErrorsIs(p, error)?.substringAfter(", ")?.removeSuffix(")")
    }

    /** `append(out, |` in a loop over a collection: the element of the loop, when its type is that of the slice. */
    fun c12AppendedElement(p: GoInlinePlace, call: GoCallExpr, index: Int): String? {
        val callee = (call.expression as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text
        if (callee != "append" || index != 1 || call.arguments.size != 2) return null
        val loop = PsiTreeUtil.getParentOfType(call, GoForStatement::class.java) ?: return null
        val clause = loop.rangeClause ?: return null
        val element = clause.varDefinitionList.getOrNull(1) ?: clause.varDefinitionList.singleOrNull()?.takeIf { p.typeOf(clause.expression ?: return null).underlying() is io.github.golangsupport.semantic.types.GoChanType }
            ?: return null
        val name = element.name?.takeIf { it != "_" } ?: return null
        val slice = (call.arguments[0] as? GoExpression)?.let { p.elementOf(p.typeOf(it)) } ?: return null
        val type = p.typeOf(element)
        return if (GoTypePredicates.identical(type, slice) || fits(p, type, slice)) name else null
    }

    /** `t.Run(|` in the loop over the cases: `tt.name, func(t *testing.T) {` and its end. */
    fun c13TestRun(p: GoInlinePlace, call: GoCallExpr, index: Int): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier?.text != "Run" || index != 0 || call.arguments.size != 1) return null
        val receiver = callee.expression as? GoReferenceExpression ?: return null
        val t = p.variables.firstOrNull { it.name == receiver.text } ?: return null
        val kind = p.standard(t)?.takeIf { it.first == "testing" && it.third && it.second in setOf("T", "B") }?.second ?: return null
        val loop = PsiTreeUtil.getParentOfType(call, GoForStatement::class.java) ?: return null
        val case = loop.rangeClause?.varDefinitionList?.lastOrNull() ?: return null
        val struct = p.structOf(p.typeOf(case)) ?: return null
        val field = struct.fields.filter { f -> f.name.lowercase() in setOf("name", "desc", "description", "title", "scenario") && (f.type.underlying() as? GoBasicType)?.kind?.isString == true }
            .singleOrNull() ?: return null
        val inner = p.indent + p.unit
        val close = if (p.slot.closer.isNotEmpty()) ")" else ""
        return "${case.name}.${field.name}, func(${t.name} *testing.$kind) {\n$inner\n${p.indent}}$close"
    }

    // --- composite literals (D) ---

    fun literal(p: GoInlinePlace): String? {
        val expression = p.expression ?: return null
        val value = expression.parent as? GoValue ?: return null
        val element = value.parent as? GoElement ?: return null
        val literalValue = element.parent as? GoLiteralValue ?: return null
        val composite = literalValue.parent as? GoCompositeLit ?: return null
        val struct = p.typeOf(composite).underlying() as? GoStructType ?: return null
        val field = p.slot.field
        return if (field == null) {
            if (element.key != null || PsiTreeUtil.getChildrenOfTypeAsList(literalValue, GoElement::class.java).size != 1) null
            else GoInlineLibrary.d7Server(p, p.typeOf(composite)) ?: d1AllFields(p, struct)
        } else {
            val target = struct.fields.firstOrNull { it.name == field } ?: return null
            d2SameName(p, target.name, target.type) ?: d3ParameterField(p, target.name, target.type)
                ?: GoInlineLibrary.d4Now(p, target.name, target.type) ?: GoInlineLibrary.d5Service(p, target.type)
        }
    }

    private fun sameName(p: GoInlinePlace, field: String, type: GoType): String? =
        p.variables.filter { it.name.equals(field, ignoreCase = true) && fits(p, it.type, type) }.singleOrNull()?.name

    /** `T{` with variables of the names and types of the fields in scope: `Name: name, Email: email`. */
    fun d1AllFields(p: GoInlinePlace, struct: GoStructType): String? {
        val pairs = struct.fields.filter { !it.embedded }.mapNotNull { field -> sameName(p, field.name, field.type)?.let { "${field.name}: $it" } }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** `T{Name: |` with a `name` in scope. */
    fun d2SameName(p: GoInlinePlace, field: String, type: GoType): String? = sameName(p, field, type)

    /** `T{Name: |` with a parameter `req` whose struct has `Name`: `req.Name`. */
    fun d3ParameterField(p: GoInlinePlace, field: String, type: GoType): String? {
        val candidates = p.variables.filter { it.element is GoParamDefinition || it.element is GoReceiver }.flatMap { v ->
            p.structOf(v.type)?.fields.orEmpty().filter { !it.embedded && it.name.equals(field, ignoreCase = true) && fits(p, it.type, type) }.map { "${v.name}.${it.name}" }
        }
        return candidates.singleOrNull()
    }

    // --- range and if (E) ---

    private val INDEX_NAMES = setOf("i", "j", "k", "n", "idx", "index", "_")

    fun range(p: GoInlinePlace): String? {
        val clause = PsiTreeUtil.getParentOfType(p.leaf, GoRangeClause::class.java) ?: return null
        if (clause.expression?.textRange?.startOffset != p.leaf.textRange.startOffset) return null
        val names = p.slot.names
        val singular = names.getOrNull(1)?.takeIf { it != "_" } ?: names[0].takeIf { names.size == 1 && it !in INDEX_NAMES }
        val found = e1PluralCollection(p, singular) ?: e2OnlyCollection(p, names) ?: return null
        return found + if (p.slot.rest.isEmpty()) " {" else ""
    }

    /** `for _, user := range |`: `users`. */
    fun e1PluralCollection(p: GoInlinePlace, singular: String?): String? {
        singular ?: return null
        val plurals = GoInlineSuggestions.plurals(singular).map { it.lowercase() }.toSet()
        return p.variables.filter { it.name.lowercase() in plurals && p.isRangeable(it.type) }.singleOrNull()?.name
    }

    /** The only collection in scope (or the only parameter that is one): `for i, x := range |`. */
    fun e2OnlyCollection(p: GoInlinePlace, names: List<String>): String? {
        val collections = p.variables.filter { it.name !in names && p.isRangeable(it.type) }
        return (collections.singleOrNull() ?: collections.filter { it.isParameter }.singleOrNull())?.name
    }

    fun condition(p: GoInlinePlace): String? {
        val statement = p.statement as? GoIfStatement ?: return null
        val previous = PsiTreeUtil.getPrevSiblingOfType(statement, GoStatement::class.java)
        val found = when {
            previous == null -> GoInlineFlow.firstCondition(p, statement)
            // `if errors.|`: what the error is compared with
            p.slot.typed.startsWith("errors.") -> assignedNames(previous).lastOrNull()?.takeIf { ERROR_NAME.matches(it) }?.let { GoInlineFlow.e9ErrorsIs(p, it) }
            else -> e5NotOk(p, previous) ?: e8ErrorCheck(p, previous)
        } ?: return null
        return found + if (p.slot.rest.isEmpty()) " {" else ""
    }

    private fun assignedNames(statement: GoStatement): List<String> {
        val short = statement as? GoShortVarDeclaration ?: PsiTreeUtil.getChildOfType(statement, GoShortVarDeclaration::class.java)
        if (short != null) return short.varDefinitionList.mapNotNull { it.name }
        val assignment = statement as? GoAssignmentStatement ?: PsiTreeUtil.getChildOfType(statement, GoAssignmentStatement::class.java) ?: return emptyList()
        return assignment.leftHandExprList?.expressionList.orEmpty().map { it.text }
    }

    /** `if |` after `v, ok := …`: `!ok`. */
    fun e5NotOk(p: GoInlinePlace, previous: GoStatement): String? {
        val names = assignedNames(previous)
        val ok = names.lastOrNull()?.takeIf { names.size >= 2 && it in OK_NAMES } ?: return null
        return "!$ok"
    }

    /** `if |` after `x, err := …`: `err != nil` (what [GoIdioms] gives on a line of its own). */
    fun e8ErrorCheck(p: GoInlinePlace, previous: GoStatement): String? {
        val error = assignedNames(previous).lastOrNull()?.takeIf { ERROR_NAME.matches(it) } ?: return null
        val variable = p.variables.firstOrNull { it.name == error } ?: return null
        if (variable.type !is GoUnknownType && !GoReturnValues.isError(variable.type)) return null
        return "$error != nil"
    }
}
