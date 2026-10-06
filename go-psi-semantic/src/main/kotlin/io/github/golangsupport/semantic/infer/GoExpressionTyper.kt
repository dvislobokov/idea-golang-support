package io.github.golangsupport.semantic.infer

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.indices
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.psi.GoPsiUtil.literalType
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.recvStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.psi.GoPsiUtil.typeArgumentTypes
import io.github.golangsupport.semantic.psi.GoPsiUtil.types
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoTypeSwitchGuard
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoValue
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoType as PsiType
import io.github.golangsupport.lang.psi.GoStructType as PsiStructType
import io.github.golangsupport.semantic.types.*
import io.github.golangsupport.semantic.types.GoTypePredicates.defaultType
import io.github.golangsupport.semantic.types.GoTypePredicates.isUntyped

/**
 * Expression typing (`go/types` expr.go, call.go, builtins.go, subset) and constant evaluation.
 * Every result is cached through [GoBodyCache] (the body store of the enclosing function, or a
 * `CachedValue` on package-level elements); nothing here throws.
 */
@Service(Service.Level.PROJECT)
class GoExpressionTyper(private val project: Project) {

    private val log = logger<GoExpressionTyper>()
    private val trackers get() = GoTrackers.getInstance(project)
    private val resolver get() = GoResolver.getInstance(project)
    private val packages get() = GoPackageModel.getInstance(project)
    val builder: GoTypeBuilder by lazy { GoTypeBuilder(project) }

    /** The type of an expression; a type expression (`T` used as a value, e.g. in a conversion) yields the type itself. */
    fun typeOf(expr: GoExpression): GoType {
        if (expr is GoBinaryExpr) warmLeftSpine(expr, warmingTypes, TYPE_KEY) { typeOfCached(it) }
        return typeOfCached(expr)
    }

    /** The single cache entry point for expression types ([TYPE_KEY]): warming must go through it so the warmed values are the ones looked up. */
    private fun typeOfCached(expr: GoExpression): GoType = cached(expr) { computeType(expr) }

    /**
     * Generated code has left-deep operator chains thousands of operands long (the `""+"..."+...`
     * tables of x/text/unicode/norm). Computing them top-down recurses once per operand through
     * the cache and RecursionManager and overflows the stack. Evaluating the left spine bottom-up
     * first makes every level find its left operand cached, so the recursion depth stays constant.
     */
    private inline fun warmLeftSpine(expr: GoBinaryExpr, warming: ThreadLocal<Boolean>, key: com.intellij.openapi.util.Key<*>, compute: (GoExpression) -> Unit) {
        if (warming.get() == true) return
        // Cheap guard (text length is O(1)): a chain of SPINE_WARM_THRESHOLD operands needs at least
        // one character per operand and per operator, so shorter expressions never walk the spine.
        if (expr.textLength < 2 * SPINE_WARM_THRESHOLD) return
        // A cached left operand means the spine below it is warm: walking it again for every level
        // (the checker visits each level) was quadratic in the chain length.
        val left = expr.left
        if (left == null || GoBodyCache.isCached(left, key)) return
        val spine = ArrayList<GoBinaryExpr>()
        var cur: GoExpression? = expr.left
        while (cur is GoBinaryExpr) { spine.add(cur); cur = cur.left }
        if (spine.size < SPINE_WARM_THRESHOLD) return
        warming.set(true)
        try {
            for (i in spine.indices.reversed()) compute(spine[i])
        } finally {
            warming.set(false)
        }
    }

    /** The compile-time constant value of [expr], or null when it is not a constant we can evaluate. */
    fun constantOf(expr: GoExpression): GoConstant? {
        if (expr is GoBinaryExpr && iotaOverride.get() == null) {
            // Constant folding asks for operand types: warm the types of the spine first, with their own guard.
            warmLeftSpine(expr, warmingTypes, TYPE_KEY) { typeOfCached(it) }
            warmLeftSpine(expr, warmingConstants, CONSTANT_KEY) { constantOfImpl(it) }
        }
        return constantOfImpl(expr)
    }

    private fun constantOfImpl(expr: GoExpression): GoConstant? = if (iotaOverride.get() != null) {
        // Evaluating a const spec with a specific iota (implicit repetition): bypass the per-expression cache.
        RecursionManager.doPreventingRecursion(expr, true) { runCatching { computeConstant(expr) }.onFailure { if (it !is Exception || it is com.intellij.openapi.progress.ProcessCanceledException) throw it }.getOrNull() }
    } else GoBodyCache.cached(expr, CONSTANT_KEY) {
        RecursionManager.doPreventingRecursion(expr, true) { runCatching { computeConstant(expr) }.onFailure { if (it is Exception && it !is com.intellij.openapi.progress.ProcessCanceledException) log.debug("constant", it) else throw it }.getOrNull() }
    }

    /** Whether [expr] denotes a type (named type, type parameter, builtin type) rather than a value. */
    fun isTypeExpression(expr: GoExpression): Boolean = when (expr) {
        is GoReferenceExpression -> {
            val results = resolver.resolveReferenceExpression(expr)
            val e = results.firstOrNull()?.element
            e is GoTypeSpec || e is GoTypeParamDefinition ||
                // `EI` in `func (d *T[EI, E]) m() { EI(n) }`: a receiver type parameter name used as a type.
                (e != null && GoScopes.receiverTypeParamOf(e) != null) ||
                (expr.qualifier == null && results.isEmpty() && (expr.referenceName in GoUniverse.BASIC_TYPES || expr.referenceName in GoUniverse.SPECIAL_TYPES))
        }
        is GoParenthesesExpr -> { val i = expr.inner; i is PsiType || (i is GoExpression && isTypeExpression(i)) }
        is GoUnaryExpr -> expr.operator === GoTypes.MUL && expr.expression?.let(::isTypeExpression) == true
        is GoIndexOrSliceExpr -> !expr.isSlice && expr.expression?.let(::isTypeExpression) == true
        else -> false
    }

    /** Types of expressions, variables, constants and literal values (one key: the element kinds are disjoint). */
    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(expr: PsiElement, compute: () -> T): T = (GoBodyCache.cached(expr, TYPE_KEY) {
        RecursionManager.doPreventingRecursion(expr, true) { runCatching(compute).onFailure { if (it is Exception && it !is com.intellij.openapi.progress.ProcessCanceledException) log.debug("typeOf", it) else throw it }.getOrNull() }
    } ?: GoUnknownType) as T

    private fun deps(e: PsiElement): Array<Any> = trackers.dependencies(e)

    // --- expressions ---

    private fun computeType(expr: GoExpression): GoType = when (expr) {
        is GoLiteral -> literalType(expr)
        is GoStringLiteral -> GoBasicType.UNTYPED_STRING
        is GoParenthesesExpr -> when (val i = expr.inner) { is GoExpression -> typeOf(i); is PsiType -> builder.typeOf(i); else -> GoUnknownType }
        is GoReferenceExpression -> referenceType(expr)
        is GoCallExpr -> callType(expr)
        is GoCompositeLit -> compositeLitType(expr)
        is GoFunctionLit -> builder.signatureOf(expr.signature, null, null)
        is GoIndexOrSliceExpr -> indexType(expr)
        is GoTypeAssertionExpr -> expr.type?.let { builder.typeOf(it) } ?: GoUnknownType
        is GoConversionExpr -> expr.type?.let { builder.typeOf(it) } ?: GoUnknownType
        is GoUnaryExpr -> unaryType(expr)
        is GoBinaryExpr -> binaryType(expr)
        else -> GoUnknownType
    }

    private fun literalType(lit: GoLiteral): GoType = when {
        lit.int != null -> GoBasicType.UNTYPED_INT
        lit.float != null -> GoBasicType.UNTYPED_FLOAT
        lit.imag != null -> GoBasicType.UNTYPED_COMPLEX
        lit.char != null -> GoBasicType.UNTYPED_RUNE
        else -> GoUnknownType
    }

    private fun referenceType(ref: GoReferenceExpression): GoType {
        val results = resolver.resolveReferenceExpression(ref)
        if (results.isEmpty()) {
            val name = ref.referenceName ?: return GoUnknownType
            if (ref.qualifier == null) return universeValueType(name)
            return GoUnknownType
        }
        return when (val r = results[0]) {
            is GoResolver.Result.Declaration -> {
                val d = r.element
                if (d is GoVarDefinition && d.parent is GoTypeSwitchGuard) typeSwitchBindingTypeAt(d, ref) else declarationType(d, ref)
            }
            is GoResolver.Result.Member -> declarationType(r.element, ref)
            is GoResolver.Result.Selection -> selectionType(r.selection, ref)
            is GoResolver.Result.ReceiverTypeParam -> builder.typeParam(r.definition)
            is GoResolver.Result.Import, is GoResolver.Result.Package, is GoResolver.Result.Cgo, is GoResolver.Result.Label -> GoUnknownType
        }
    }

    private fun universeValueType(name: String): GoType = when (name) {
        "true", "false" -> GoBasicType.UNTYPED_BOOL
        "nil" -> GoBasicType.UNTYPED_NIL
        "iota" -> GoBasicType.UNTYPED_INT
        else -> GoBasicType.byName(name) ?: when (name) { "any" -> GoTypeBuilder.ANY; "error" -> GoTypeBuilder.ERROR; "comparable" -> GoTypeBuilder.COMPARABLE; else -> GoUnknownType }
    }

    private fun selectionType(sel: GoLookup.Selection, ref: GoReferenceExpression): GoType = when (sel) {
        is GoLookup.Selection.Field -> sel.member.type
        is GoLookup.Selection.Method -> {
            val qualifier = ref.qualifier
            val sig = sel.method.signature
            // Method expression `T.M` / `(*T).M`: a function whose first parameter is the qualifier type.
            if (qualifier != null && isTypeExpression(qualifier)) {
                val qt = typeOf(qualifier)
                if (sel.method.pointerReceiver && qt !is GoPointerType && qt.underlying() !is GoInterfaceType) GoUnknownType
                else GoSignatureType(listOf(GoParam(null, qt)) + sig.params, sig.results, sig.variadic, sig.typeParams) // Go 1.27: a generic method keeps its own type parameters
            } else sig
        }
        is GoLookup.Selection.Ambiguous -> GoUnknownType
    }

    /** The type of a declaration used as a value: variable, constant, function, parameter, field, or a type (for conversions). */
    fun declarationType(decl: GoNamedElement, place: PsiElement?): GoType = when (decl) {
        is GoTypeSpec, is GoTypeParamDefinition -> builder.typeOfDeclaration(decl, place ?: decl)
        is GoVarDefinition -> if (GoUniverse.isBuiltinDeclaration(decl)) universeValueType(decl.name ?: "") else varType(decl)
        is GoConstDefinition -> if (GoUniverse.isBuiltinDeclaration(decl)) universeValueType(decl.name ?: "") else constType(decl)
        is GoFunctionDeclaration -> if (GoUniverse.isBuiltinDeclaration(decl)) GoUnknownType else builder.functionType(decl)
        is GoMethodDeclaration -> builder.methodOf(decl, null)?.signature ?: GoUnknownType
        is GoParamDefinition -> paramType(decl)
        is GoReceiver -> decl.type?.let { builder.typeOf(it) } ?: GoUnknownType
        is GoFieldDefinition -> (decl.parent as? GoFieldDeclaration)?.type?.let { builder.typeOf(it) } ?: GoUnknownType
        is GoAnonymousFieldDefinition -> {
            val struct = decl.parent?.parent as? PsiStructType
            (struct?.let { builder.typeOf(it) } as? GoStructType)?.fields?.firstOrNull { f -> f.declaration == decl }?.type ?: GoUnknownType
        }
        is GoMethodSpec -> builder.signatureOf(decl.signature, null, null)
        else -> GoUnknownType
    }

    private fun paramType(def: GoParamDefinition): GoType {
        val decl = def.parent as? GoParameterDeclaration ?: return GoUnknownType
        var t: GoType = decl.type?.let { builder.typeOf(it) } ?: GoUnknownType
        if (decl.isVariadic) t = GoSliceType(t)
        return t
    }

    private fun varType(def: GoVarDefinition): GoType = cached(def) {
        when (val parent = def.parent) {
            is GoVarSpec -> {
                val declared = parent.type
                if (declared != null) builder.typeOf(declared)
                else valueTypeAt(parent.expressionList, parent.varDefinitionList.indexOf(def), parent.varDefinitionList.size)
            }
            is GoShortVarDeclaration -> valueTypeAt(parent.expressionList, parent.varDefinitionList.indexOf(def), parent.varDefinitionList.size)
            is GoRangeClause -> rangeVarType(parent, parent.varDefinitionList.indexOf(def))
            is GoRecvStatement -> {
                val chanType = parent.expression?.let(::typeOf)?.let { recvType(it) }
                val idx = parent.varDefinitionList.indexOf(def)
                if (idx == 0) chanType ?: GoUnknownType else GoBasicType.BOOL
            }
            is GoTypeSwitchGuard -> typeSwitchBindingType(parent, def)
            else -> GoUnknownType
        }
    }

    /** `x := v.(type)`: inside a single-type case the binding has that type, otherwise the guard's type. */
    private fun typeSwitchBindingType(guard: GoTypeSwitchGuard, def: GoVarDefinition): GoType {
        // The binding is referenced from a case clause; callers resolve it through the clause.
        return guard.expression?.let(::typeOf) ?: GoUnknownType
    }

    /** The type of the type-switch binding as seen from [place] (inside a case clause). */
    fun typeSwitchBindingTypeAt(def: GoVarDefinition, place: PsiElement): GoType {
        val guard = def.parent as? GoTypeSwitchGuard ?: return GoUnknownType
        var e: PsiElement? = place
        while (e != null && !(e is GoTypeCaseClause && GoPsiUtil.run { (e.parent as? io.github.golangsupport.lang.psi.GoTypeSwitchStatement)?.guard } === guard)) e = e.parent
        val clause = e as? GoTypeCaseClause ?: return typeSwitchBindingType(guard, def)
        val types = clause.types
        if (types.size == 1) {
            val t = builder.typeOf(types[0])
            // `case nil` keeps the interface type.
            if (types[0].typeReferenceExpression?.identifier?.text == "nil" && t is GoUnknownType) return typeSwitchBindingType(guard, def)
            return t
        }
        return typeSwitchBindingType(guard, def)
    }

    private fun valueTypeAt(values: List<GoExpression>, index: Int, count: Int): GoType {
        if (index < 0) return GoUnknownType
        if (values.size == count) return defaultType(typeOf(values[index]))
        if (values.size == 1) {
            val single = values[0]
            val t = typeOf(single)
            if (t is GoTupleType) return t.types.getOrNull(index) ?: GoUnknownType
            // `v, ok := m[k]`, `v, ok := x.(T)`, `v, ok := <-ch`
            if (count == 2) return if (index == 0) defaultType(t) else GoBasicType.BOOL
            return defaultType(t)
        }
        return GoUnknownType
    }

    private fun constType(def: GoConstDefinition): GoType = cached(def) {
        val spec = def.parent as? GoConstSpec ?: return@cached GoUnknownType
        val index = spec.constDefinitionList.indexOf(def)
        val (typeNode, values) = constSpecSource(spec)
        if (typeNode != null) return@cached builder.typeOf(typeNode)
        values.getOrNull(index)?.let { typeOf(it) } ?: GoUnknownType
    }

    /** The type node and values of a const spec, following implicit repetition of the previous spec. */
    private fun constSpecSource(spec: GoConstSpec): Pair<PsiType?, List<GoExpression>> {
        val s = repeatedConstSpec(spec) ?: return null to emptyList()
        return s.type to s.expressionList
    }

    /**
     * The spec whose type and values [spec] uses: [spec] itself when it has a type or values,
     * otherwise the nearest previous spec of the same declaration that has one (implicit
     * repetition), or null.
     */
    fun repeatedConstSpec(spec: GoConstSpec): GoConstSpec? {
        constSpecLayout(spec)?.let { layout -> layout.index[spec]?.let { return layout.source[it] } }
        var s: GoConstSpec? = spec
        while (s != null) {
            if (s.expressionList.isNotEmpty() || s.type != null) return s
            var e = s.prevSibling
            while (e != null && e !is GoConstSpec) e = e.prevSibling
            s = e as GoConstSpec?
        }
        return null
    }

    fun iotaOf(spec: GoConstSpec): Int {
        constSpecLayout(spec)?.index?.get(spec)?.let { return it }
        var n = 0
        var e = spec.prevSibling
        while (e != null) { if (e is GoConstSpec) n++; e = e.prevSibling }
        return n
    }

    /**
     * Iota and repetition source of every spec of a `const (...)` group, computed in one pass.
     * Walking the siblings per spec was quadratic: `cmd/compile/internal/ssa/opGen.go` has one
     * group of several thousand implicitly repeated specs.
     */
    private class ConstSpecLayout(val index: Map<GoConstSpec, Int>, val source: Array<GoConstSpec?>)

    private fun constSpecLayout(spec: GoConstSpec): ConstSpecLayout? {
        val decl = spec.parent ?: return null
        return CachedValuesManager.getCachedValue(decl, CONST_LAYOUT_KEY) {
            val index = HashMap<GoConstSpec, Int>()
            val source = ArrayList<GoConstSpec?>()
            var last: GoConstSpec? = null
            var e = decl.firstChild
            while (e != null) {
                if (e is GoConstSpec) {
                    if (e.expressionList.isNotEmpty() || e.type != null) last = e
                    index[e] = source.size
                    source.add(last)
                }
                e = e.nextSibling
            }
            CachedValueProvider.Result.create(ConstSpecLayout(index, source.toTypedArray()), *deps(decl))
        }
    }

    // --- range ---

    private fun rangeVarType(clause: GoRangeClause, index: Int): GoType {
        val x = clause.expression?.let(::typeOf) ?: return GoUnknownType
        return rangeTypes(x).getOrNull(index) ?: GoUnknownType
    }

    /** The (key, value) types produced by `range x` (spec "For statements with range clause"). */
    fun rangeTypes(x: GoType): List<GoType> {
        val u = if (x is GoTypeParamType) x.coreType ?: x.underlying() else x.underlying()
        return when (u) {
            is GoBasicType -> when {
                u.kind.isString -> listOf(GoBasicType.INT, GoBasicType.RUNE)
                u.kind.isInteger -> listOf(if (u.isUntyped) GoBasicType.INT else x)
                else -> emptyList()
            }
            is GoArrayType -> listOf(GoBasicType.INT, u.elem)
            is GoPointerType -> (u.elem.underlying() as? GoArrayType)?.let { listOf(GoBasicType.INT, it.elem) } ?: emptyList()
            is GoSliceType -> listOf(GoBasicType.INT, u.elem)
            is GoMapType -> listOf(u.key, u.value)
            is GoChanType -> listOf(u.elem)
            is GoSignatureType -> {
                // func(yield func(K, V) bool)
                val yield = u.params.singleOrNull()?.type?.underlying() as? GoSignatureType ?: return emptyList()
                yield.params.map { it.type }
            }
            else -> emptyList()
        }
    }

    private fun recvType(chan: GoType): GoType? = (chan.underlying() as? GoChanType)?.elem

    // --- calls ---

    private fun callType(call: GoCallExpr): GoType {
        val callee = call.expression ?: return GoUnknownType
        unsafeCallType(call, callee)?.let { return it }
        // Conversion to a parenthesised/pointer/literal type: `(*T)(x)`, `[]byte(s)` handled by ConversionExpr; here `T(x)`.
        val calleeRef = unparen(callee)
        if (calleeRef is GoReferenceExpression && calleeRef.qualifier == null) {
            val name = calleeRef.referenceName ?: ""
            val results = resolver.resolveReferenceExpression(calleeRef)
            if (results.isEmpty() && name in GoUniverse.FUNCTIONS) return builtinCallType(name, call)
            val decl = results.firstOrNull()?.element
            if (decl is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(decl)) return builtinCallType(name, call)
        }
        if (isTypeExpression(callee)) {
            val t = typeOf(callee)
            return t
        }
        return calleeSignature(call)?.resultType ?: GoUnknownType
    }

    /**
     * The signature a call invokes, with inferred type arguments substituted (`go/types` infer.go):
     * null for builtins, conversions and non-function callees. Cached per call.
     */
    fun calleeSignature(call: GoCallExpr): GoSignatureType? = cachedNullable(call, SIGNATURE_KEY) {
        val callee = call.expression ?: return@cachedNullable null
        if (isTypeExpression(callee)) return@cachedNullable null
        val calleeType = typeOf(callee)
        val sig = (if (calleeType is GoTypeParamType) calleeType.coreType else calleeType.underlying()) as? GoSignatureType ?: return@cachedNullable null
        if (!sig.isGeneric) return@cachedNullable sig
        val args = call.arguments.map { a ->
            when (a) {
                is GoExpression -> GoInference.Arg(typeOf(a))
                is PsiType -> GoInference.Arg(builder.typeOf(a))
                else -> GoInference.Arg(GoUnknownType)
            }
        }
        val subst = GoInference.infer(sig, args, spread = call.argumentList?.hasEllipsis == true)
        sig.substitute(subst) as GoSignatureType
    }

    private fun <T : Any> cachedNullable(element: PsiElement, key: com.intellij.openapi.util.Key<com.intellij.psi.util.CachedValue<T?>>, compute: () -> T?): T? =
        GoBodyCache.cached(element, key) {
            RecursionManager.doPreventingRecursion(element, true) { runCatching(compute).onFailure { if (it is Exception && it !is com.intellij.openapi.progress.ProcessCanceledException) log.debug("calleeSignature", it) else throw it }.getOrNull() }
        }

    private fun builtinCallType(name: String, call: GoCallExpr): GoType {
        val args = call.arguments
        fun argType(i: Int): GoType = when (val a = args.getOrNull(i)) { is GoExpression -> typeOf(a); is PsiType -> builder.typeOf(a); else -> GoUnknownType }
        fun typeArg(i: Int): GoType = when (val a = args.getOrNull(i)) { is PsiType -> builder.typeOf(a); is GoExpression -> typeOf(a); else -> GoUnknownType }
        return when (name) {
            "len", "cap" -> GoBasicType.INT
            "append" -> argType(0).let { if (it is GoBasicType && it.kind == GoBasicKind.UNTYPED_NIL) GoUnknownType else if (it is GoTupleType) it.types.firstOrNull() ?: GoUnknownType else it }
            "make" -> typeArg(0)
            // Go 1.26: `new(expr)` allocates a variable of the (default) type of the expression.
            "new" -> GoPointerType(args.getOrNull(0).let { a -> if (a is PsiType) builder.typeOf(a) else if (a is GoExpression) (if (isTypeExpression(a)) typeOf(a) else defaultType(typeOf(a))) else GoUnknownType })
            "copy" -> GoBasicType.INT
            "delete", "panic", "print", "println", "clear", "close" -> GoTupleType(emptyList())
            "recover" -> GoTypeBuilder.ANY
            "complex" -> {
                val a = argType(0).underlying() as? GoBasicType
                when (a?.kind) { GoBasicKind.FLOAT32 -> GoBasicType.COMPLEX64; GoBasicKind.FLOAT64 -> GoBasicType.COMPLEX128; else -> if (a != null && a.isUntyped) GoBasicType.UNTYPED_COMPLEX else GoBasicType.COMPLEX128 }
            }
            "real", "imag" -> {
                val a = argType(0).underlying() as? GoBasicType
                when (a?.kind) { GoBasicKind.COMPLEX64 -> GoBasicType.FLOAT32; GoBasicKind.COMPLEX128 -> GoBasicType.FLOAT64; else -> if (a != null && a.isUntyped) GoBasicType.UNTYPED_FLOAT else GoBasicType.FLOAT64 }
            }
            "min", "max" -> {
                var result: GoType = GoUnknownType
                for (i in args.indices) {
                    val t = argType(i)
                    if (!isUntyped(t)) { result = t; break }
                    if (result is GoUnknownType || t is GoBasicType && result is GoBasicType && (t as GoBasicType).kind.ordinal > (result as GoBasicType).kind.ordinal) result = t
                }
                result
            }
            else -> GoUnknownType
        }
    }

    /**
     * `unsafe.Sizeof(x)`, `Alignof(x)`, `Offsetof(x.f)` folded with gc sizes ([GoSizes]); null when
     * the operand's layout depends on a type parameter or is unknown. `Offsetof` sums the offsets
     * along an embedding path without pointer indirections.
     */
    private fun unsafeSizeConstant(name: String?, arg: GoExpression): GoConstant? {
        val value: Long? = when (name) {
            "Sizeof" -> GoSizes.sizeof(typeOf(arg).takeIf { it !is GoTupleType } ?: return null)
            "Alignof" -> GoSizes.alignof(typeOf(arg).takeIf { it !is GoTupleType } ?: return null)
            "Offsetof" -> {
                val sel = unparen(arg) as? GoReferenceExpression ?: return null
                val x = sel.qualifier as? GoExpression ?: return null
                val selection = (resolver.resolveReferenceExpression(sel).firstOrNull() as? GoResolver.Result.Selection)?.selection as? GoLookup.Selection.Field ?: return null
                var t: GoType = typeOf(x).let { (it as? GoPointerType)?.elem ?: it }
                var offset = 0L
                for (f in selection.path + selection.member) {
                    val s = t.underlying() as? GoStructType ?: return null
                    val i = s.fields.indexOfFirst { it.name == f.name }.takeIf { it >= 0 } ?: return null
                    offset += GoSizes.offsetsof(s)?.get(i) ?: return null
                    t = s.fields[i].type
                    if (f !== selection.member && t is GoPointerType) return null
                }
                offset
            }
            else -> null
        }
        return value?.let { GoConstant.Int(it) }
    }

    /** `unsafe.Sizeof/Alignof/Offsetof` -> uintptr, `Slice(*T, n)` -> []T, `SliceData([]T)` -> *T, `String` -> string, `StringData` -> *byte, `Add` -> Pointer. */
    private fun unsafeCallType(call: GoCallExpr, callee: GoExpression): GoType? {
        val ref = unparen(callee) as? GoReferenceExpression ?: return null
        val q = ref.qualifier as? GoReferenceExpression ?: return null
        if (q.qualifier != null) return null
        val import = resolver.resolveReferenceExpression(q).firstOrNull { it is GoResolver.Result.Import } as? GoResolver.Result.Import ?: return null
        if (import.element.path != "unsafe") return null
        val args = call.arguments
        fun argType(i: Int): GoType = (args.getOrNull(i) as? GoExpression)?.let(::typeOf) ?: GoUnknownType
        return when (ref.referenceName) {
            "Sizeof", "Alignof", "Offsetof" -> GoBasicType.UINTPTR
            "Add" -> GoBasicType.UNSAFE_POINTER
            "Slice" -> (argType(0).underlying() as? GoPointerType)?.let { GoSliceType(it.elem) } ?: GoUnknownType
            "SliceData" -> (argType(0).underlying() as? GoSliceType)?.let { GoPointerType(it.elem) } ?: GoUnknownType
            "String" -> GoBasicType.STRING
            "StringData" -> GoPointerType(GoBasicType.BYTE)
            else -> null
        }
    }

    private fun hasCallOrReceive(e: GoExpression): Boolean =
        com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(e, GoCallExpr::class.java).any { !isTypeExpression(it.expression ?: return@any false) } ||
            com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(e, GoUnaryExpr::class.java).any { it.operator === GoTypes.ARROW } ||
            (e is GoCallExpr && !isTypeExpression(e.expression ?: return true)) || (e is GoUnaryExpr && e.operator === GoTypes.ARROW)

    private fun unparen(e: GoExpression): GoExpression {
        var x = e
        while (x is GoParenthesesExpr) x = x.inner as? GoExpression ?: return x
        return x
    }

    // --- composite literals ---

    private fun compositeLitType(lit: GoCompositeLit): GoType {
        val typeNode = lit.literalType
        val base = when (typeNode) {
            is PsiType -> builder.typeOf(typeNode)
            is GoTypeReferenceExpression -> builder.typeOfReference(typeNode)
            else -> return GoUnknownType
        }
        val args = lit.typeArgumentTypes?.map { builder.typeOf(it) }
        var t = if (args != null && base is GoNamedType) base.instantiate(args) else base
        // `[...]T{...}`: the array length is the element count.
        if (t is GoArrayType && t.length == null) t = GoArrayType(t.elem, countElements(lit.literalValue))
        return t
    }

    private fun countElements(value: GoLiteralValue?): Long? {
        value ?: return null
        var max = -1L
        var index = -1L
        for (e in value.elements) {
            val key = e.key?.expression
            index = if (key != null) constantOf(key)?.toBigInteger()?.toLong() ?: (index + 1) else index + 1
            if (index > max) max = index
        }
        return max + 1
    }

    /** The type of the values of a literal value node (`{...}`), following elided types in nested literals. */
    fun typeOfLiteralValue(value: GoLiteralValue): GoType? = cached(value) { computeLiteralValueType(value) }

    private fun computeLiteralValueType(value: GoLiteralValue): GoType? {
        when (val parent = value.parent) {
            is GoCompositeLit -> return typeOf(parent)
            is GoValue -> {
                val element = parent.parent as? GoElement ?: return null
                val outer = (element.parent as? GoLiteralValue)?.let(::typeOfLiteralValue) ?: return null
                return elementValueType(outer)
            }
            is GoKey -> {
                val element = parent.parent as? GoElement ?: return null
                val outer = (element.parent as? GoLiteralValue)?.let(::typeOfLiteralValue) ?: return null
                return (outer.underlying() as? GoMapType)?.key
            }
            else -> return null
        }
    }

    /** The element type of a literal of type [t]: slice/array elements, map values, pointer to struct for `&T{}`-less nesting. */
    fun elementValueType(t: GoType): GoType? = when (val u = (if (t is GoTypeParamType) t.coreType else null) ?: t.underlying()) {
        is GoSliceType -> deref(u.elem)
        is GoArrayType -> deref(u.elem)
        is GoMapType -> deref(u.value)
        else -> null
    }

    private fun deref(t: GoType): GoType = if (t is GoPointerType) t.elem else t

    // --- index / slice / instantiation ---

    private fun indexType(expr: GoIndexOrSliceExpr): GoType {
        val x = expr.expression ?: return GoUnknownType
        val xt = typeOf(x)
        if (expr.isSlice) {
            val u = xt.underlying()
            return when {
                u is GoBasicType && u.kind.isString -> if (u.isUntyped) GoBasicType.STRING else xt
                u is GoArrayType -> GoSliceType(u.elem)
                u is GoPointerType && u.elem.underlying() is GoArrayType -> GoSliceType((u.elem.underlying() as GoArrayType).elem)
                u is GoSliceType -> xt
                else -> GoUnknownType
            }
        }
        // Explicit instantiation of a generic function or type.
        if (xt is GoSignatureType && xt.isGeneric || isTypeExpression(x)) {
            val targs = expr.indices.map { when (it) { is PsiType -> builder.typeOf(it); is GoExpression -> typeOf(it); else -> GoUnknownType } }
            return when {
                xt is GoSignatureType -> xt.substitute(GoSubstitution.of(xt.typeParams, targs))
                xt is GoNamedType -> xt.instantiate(targs)
                else -> GoUnknownType
            }
        }
        val u = (if (xt is GoTypeParamType) xt.coreType else null) ?: xt.underlying()
        return when (u) {
            is GoBasicType -> if (u.kind.isString) GoBasicType.BYTE else GoUnknownType
            is GoArrayType -> u.elem
            is GoSliceType -> u.elem
            is GoPointerType -> (u.elem.underlying() as? GoArrayType)?.elem ?: GoUnknownType
            is GoMapType -> u.value
            else -> GoUnknownType
        }
    }

    // --- operators ---

    private fun unaryType(expr: GoUnaryExpr): GoType {
        val operand = expr.expression ?: return GoUnknownType
        val t = typeOf(operand)
        if (t is GoTupleType) return GoUnknownType
        return when (expr.operator) {
            GoTypes.AND -> if (isTypeExpression(operand)) GoPointerType(t) else if (constantOf(operand) != null && operand !is GoCompositeLit) GoUnknownType else GoPointerType(t)
            GoTypes.MUL -> if (isTypeExpression(operand)) GoPointerType(t) else (t.underlying() as? GoPointerType)?.elem ?: (if (t is GoTypeParamType) (t.coreType as? GoPointerType)?.elem else null) ?: GoUnknownType
            GoTypes.ARROW -> recvType(t) ?: GoUnknownType
            GoTypes.NOT -> if (isUntyped(t)) GoBasicType.UNTYPED_BOOL else t
            else -> t
        }
    }

    private fun binaryType(expr: GoBinaryExpr): GoType {
        val op = expr.operator
        val left = expr.left ?: return GoUnknownType
        val right = expr.right
        val lt = typeOf(left)
        val rt = right?.let(::typeOf) ?: GoUnknownType
        if (lt is GoTupleType || rt is GoTupleType) return GoUnknownType
        when (op) {
            GoTypes.EQL, GoTypes.NEQ, GoTypes.LSS, GoTypes.LEQ, GoTypes.GTR, GoTypes.GEQ -> return GoBasicType.UNTYPED_BOOL
            GoTypes.LAND, GoTypes.LOR -> return if (isUntyped(lt) && isUntyped(rt)) GoBasicType.UNTYPED_BOOL else if (!isUntyped(lt)) lt else rt
            GoTypes.SHL, GoTypes.SHR -> {
                // The type of a shift is the type of the left operand (an untyped constant keeps its kind
                // until context fixes it; a non-integer typed operand is an error, so unknown).
                val lk = (lt.underlying() as? GoBasicType)?.kind
                return when {
                    lt is GoUnknownType || lt is GoTypeParamType -> lt
                    lk == null -> GoUnknownType
                    lk.isUntyped -> lt // the constant keeps its untyped kind until the context fixes it (go/types shift)
                    lk.isInteger -> lt
                    else -> GoUnknownType
                }
            }
        }
        if (lt is GoUnknownType) return rt
        if (rt is GoUnknownType) return lt
        val lu = isUntyped(lt)
        val ru = isUntyped(rt)
        return when {
            !lu -> lt
            !ru -> rt
            else -> {
                // Both untyped: the "larger" kind wins (int < rune < float < complex).
                val lk = (lt as GoBasicType).kind
                val rk = (rt as GoBasicType).kind
                if (lk.ordinal >= rk.ordinal) lt else rt
            }
        }
    }

    // --- constants ---

    private fun computeConstant(expr: GoExpression): GoConstant? = when (expr) {
        is GoLiteral -> when {
            expr.int != null -> GoConstant.parseInt(expr.text)
            expr.float != null -> GoConstant.parseFloat(expr.text)
            expr.imag != null -> GoConstant.parseImag(expr.text)
            expr.char != null -> GoConstant.parseRune(expr.text)
            else -> null
        }
        is GoStringLiteral -> {
            val text = expr.text
            if (text.startsWith("`")) GoConstant.Str(text.removePrefix("`").removeSuffix("`").replace("\r", ""))
            else GoConstant.unescape(text.removePrefix("\"").removeSuffix("\""))?.let(GoConstant::Str)
        }
        is GoParenthesesExpr -> (expr.inner as? GoExpression)?.let(::constantOf)
        is GoUnaryExpr -> {
            val operand = expr.expression
            val v = operand?.let(::constantOf)
            val op = expr.operator
            if (v == null) null
            else if (op === GoTypes.XOR && v is GoConstant.Int && operand != null) {
                // `^x` of a typed unsigned constant complements within the type's width (go/constant with mask).
                val k = (typeOf(operand).underlying() as? GoBasicType)?.kind
                val bits = when (k) { GoBasicKind.UINT8 -> 8; GoBasicKind.UINT16 -> 16; GoBasicKind.UINT32 -> 32; GoBasicKind.UINT, GoBasicKind.UINT64, GoBasicKind.UINTPTR -> 64; else -> 0 }
                if (bits > 0) GoConstant.Int(v.value.xor(java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE))) else GoConstant.unary("^", v)
            }
            else GoConstant.unary(when (op) { GoTypes.SUB -> "-"; GoTypes.ADD -> "+"; GoTypes.NOT -> "!"; GoTypes.XOR -> "^"; else -> "" }, v)
        }
        is GoBinaryExpr -> {
            val l = expr.left?.let(::constantOf)
            val r = expr.right?.let(::constantOf)
            val op = expr.operator?.toString() ?: ""
            if (l == null || r == null) null else GoConstant.binary(opText(expr.operator), l, r)
        }
        is GoReferenceExpression -> referenceConstant(expr)
        is GoCallExpr -> callConstant(expr)
        is GoConversionExpr -> if (isConstantType(typeOf(expr))) GoPsiUtil.children(expr, GoExpression::class.java).firstOrNull()?.let(::constantOf) else null
        else -> null
    }

    /** Spec "Constants": only conversions to basic (non type parameter) types yield constants. */
    private fun isConstantType(t: GoType): Boolean = t !is GoTypeParamType && t.underlying() is GoBasicType

    private fun opText(op: com.intellij.psi.tree.IElementType?): String = when (op) {
        GoTypes.ADD -> "+"; GoTypes.SUB -> "-"; GoTypes.MUL -> "*"; GoTypes.QUO -> "/"; GoTypes.REM -> "%"
        GoTypes.AND -> "&"; GoTypes.OR -> "|"; GoTypes.XOR -> "^"; GoTypes.AND_NOT -> "&^"; GoTypes.SHL -> "<<"; GoTypes.SHR -> ">>"
        GoTypes.LAND -> "&&"; GoTypes.LOR -> "||"; GoTypes.EQL -> "=="; GoTypes.NEQ -> "!="; GoTypes.LSS -> "<"
        GoTypes.LEQ -> "<="; GoTypes.GTR -> ">"; GoTypes.GEQ -> ">="
        else -> ""
    }

    private fun referenceConstant(ref: GoReferenceExpression): GoConstant? {
        if (ref.qualifier == null) {
            when (ref.referenceName) {
                "iota" -> {
                    iotaOverride.get()?.let { return GoConstant.Int(it.toLong()) }
                    val spec = enclosingConstSpec(ref) ?: return null
                    return GoConstant.Int(iotaOf(spec).toLong())
                }
                "true" -> if (resolver.resolveReferenceExpression(ref).isEmpty()) return GoConstant.Bool(true)
                "false" -> if (resolver.resolveReferenceExpression(ref).isEmpty()) return GoConstant.Bool(false)
            }
        }
        val decl = resolver.resolveReferenceExpression(ref).firstOrNull()?.element as? GoConstDefinition ?: return null
        if (GoUniverse.isBuiltinDeclaration(decl)) return when (decl.name) { "true" -> GoConstant.Bool(true); "false" -> GoConstant.Bool(false); else -> null }
        return constantValueOf(decl)
    }

    /** The value of a constant declaration, evaluating implicit repetition with the spec's own iota. */
    fun constantValueOf(def: GoConstDefinition): GoConstant? = GoBodyCache.cached(def, CONSTANT_KEY) {
        val spec = def.parent as? GoConstSpec
        if (spec == null) null else RecursionManager.doPreventingRecursion(def, true) {
            val index = spec.constDefinitionList.indexOf(def)
            val (_, values) = constSpecSource(spec)
            val value = values.getOrNull(index)
            if (value == null) null else withIota(iotaOf(spec)) { constantOf(value) }
        }
    }

    private val iotaOverride = ThreadLocal<Int?>()

    /** Set while [warmLeftSpine] runs for types / constants, so nested calls of the same kind do not re-walk the spine; separate guards so constant folding can still warm operand types. */
    private val warmingTypes = ThreadLocal<Boolean>()
    private val warmingConstants = ThreadLocal<Boolean>()

    private fun <T> withIota(iota: Int, block: () -> T): T {
        val prev = iotaOverride.get()
        iotaOverride.set(iota)
        try {
            // Values of a repeated spec must not be served from the cache computed for the original spec.
            return uncachedConstant(block)
        } finally { iotaOverride.set(prev) }
    }

    private fun <T> uncachedConstant(block: () -> T): T = block()

    private fun enclosingConstSpec(e: PsiElement): GoConstSpec? {
        var x: PsiElement? = e
        while (x != null && x !is GoConstSpec && x !is GoFile) x = x.parent
        return x as? GoConstSpec
    }

    private fun callConstant(call: GoCallExpr): GoConstant? {
        val callee = call.expression ?: return null
        val args = call.arguments
        if (callee is GoReferenceExpression && callee.qualifier == null) {
            val name = callee.referenceName
            if ((name == "len" || name == "cap") && args.size == 1) {
                val a = args[0] as? GoExpression ?: return null
                val c = constantOf(a) as? GoConstant.Str
                if (c != null) return if (name == "len") GoConstant.Int(c.value.toByteArray(Charsets.UTF_8).size.toLong()) else null
                // len/cap of an array (or pointer to array) is constant when the operand has no calls or receives.
                if (hasCallOrReceive(a)) return null
                val u = typeOf(a).underlying()
                val arr = u as? GoArrayType ?: (u as? GoPointerType)?.elem?.underlying() as? GoArrayType ?: return null
                return arr.length?.let { GoConstant.Int(it) }
            }
            if (name in setOf("real", "imag", "complex", "min", "max") && args.isNotEmpty() && args.all { it is GoExpression }) {
                val values = args.map { constantOf(it as GoExpression) ?: return null }
                return when (name) {
                    "real" -> (values[0] as? GoConstant.Complex)?.let { GoConstant.Float(it.re) } ?: values[0].takeIf { it !is GoConstant.Str && it !is GoConstant.Bool }
                    "imag" -> (values[0] as? GoConstant.Complex)?.let { GoConstant.Float(it.im) } ?: values[0].toBigDecimal()?.let { GoConstant.Float(java.math.BigDecimal.ZERO) }
                    "complex" -> if (values.size == 2) { val re = values[0].toBigDecimal(); val im = values[1].toBigDecimal(); if (re != null && im != null) GoConstant.Complex(re, im) else null } else null
                    "min", "max" -> values.reduce { acc, v -> val cmp = GoConstant.binary("<", acc, v) as? GoConstant.Bool ?: return null; if (name == "min") (if (cmp.value) acc else v) else (if (cmp.value) v else acc) }
                    else -> null
                }
            }
        }
        if (args.size == 1 && unsafeCallType(call, callee) == GoBasicType.UINTPTR) return unsafeSizeConstant((unparen(callee) as GoReferenceExpression).referenceName, args[0] as? GoExpression ?: return null)
        // Conversion of a constant: T(c) keeps the value.
        if (isTypeExpression(callee) && args.size == 1) return if (isConstantType(typeOf(callee))) (args[0] as? GoExpression)?.let(::constantOf) else null
        return null
    }

    companion object {
        /** Left-deep operator chains at least this long are evaluated bottom-up first. */
        private const val SPINE_WARM_THRESHOLD = 64

        private val TYPE_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<Any?>>("gopsi.type")
        private val CONSTANT_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<GoConstant?>>("gopsi.constant")
        private val CONST_LAYOUT_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<ConstSpecLayout>>("gopsi.constSpecLayout")
        private val SIGNATURE_KEY =com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<GoSignatureType?>>("gopsi.calleeSignature")

        @JvmStatic
        fun getInstance(project: Project): GoExpressionTyper = project.service()
    }
}
