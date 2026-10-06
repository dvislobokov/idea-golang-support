package io.github.golangsupport.semantic.infer

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.psi.impl.GoAnonymousFieldDefinitionMixin
import io.github.golangsupport.lang.psi.impl.GoTypeReferenceExpressionMixin
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasTilde
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic
import io.github.golangsupport.lang.stubs.GoTypeStub
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeParameters
import io.github.golangsupport.lang.psi.GoTypeParameterDeclaration
import io.github.golangsupport.lang.psi.GoConstraintElem
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoTypeList
import io.github.golangsupport.lang.psi.GoType as PsiType
import io.github.golangsupport.lang.psi.GoParType as PsiParType
import io.github.golangsupport.lang.psi.GoPointerType as PsiPointerType
import io.github.golangsupport.lang.psi.GoArrayOrSliceType as PsiArrayOrSliceType
import io.github.golangsupport.lang.psi.GoMapType as PsiMapType
import io.github.golangsupport.lang.psi.GoChannelType as PsiChannelType
import io.github.golangsupport.lang.psi.GoFunctionType as PsiFunctionType
import io.github.golangsupport.lang.psi.GoStructType as PsiStructType
import io.github.golangsupport.lang.psi.GoInterfaceType as PsiInterfaceType
import io.github.golangsupport.semantic.types.*

/**
 * Builds semantic types from type PSI (`GoType` nodes) and declarations. Named types are
 * created once per declaration (cached on the `GoTypeSpec`), their underlying type and methods
 * are resolved lazily through [GoTypeSource].
 */
class GoTypeBuilder(private val project: Project) : GoTypeSource {

    private val trackers get() = GoTrackers.getInstance(project)
    private val packages get() = GoPackageModel.getInstance(project)

    // --- named types and type parameters ---

    /** The (uninstantiated) named type of a type spec; aliases are expanded to their target. */
    fun namedType(spec: GoTypeSpec): GoType = CachedValuesManager.getCachedValue(spec) {
        val t = if (spec.isAlias && spec.typeParameters == null) {
            RecursionManager.doPreventingRecursion(spec, true) { spec.type?.let { typeOf(it) } } ?: GoUnknownType
        } else if (spec.isAlias) {
            // Generic alias: behaves like a named type whose underlying type is the aliased type.
            GoNamedType(spec, emptyList(), this)
        } else {
            GoNamedType(spec, emptyList(), this)
        }
        CachedValueProvider.Result.create(t, *dependencies(spec))
    }

    fun typeParam(def: GoTypeParamDefinition): GoTypeParamType = CachedValuesManager.getCachedValue(def) {
        val list = (def.parent as? GoTypeParameterDeclaration)?.parent as? GoTypeParameters
        val index = list?.let { GoScopes.typeParamDefinitions(it).indexOf(def) } ?: 0
        CachedValueProvider.Result.create(GoTypeParamType(def, index, this), *dependencies(def))
    }

    fun typeParams(tp: GoTypeParameters?): List<GoTypeParamType> =
        tp?.let { GoScopes.typeParamDefinitions(it).map(::typeParam) } ?: emptyList()

    override fun underlyingOf(named: GoNamedType): GoType {
        val spec = named.declaration
        val origin = named.origin
        if (origin != null) {
            val params = typeParams(spec.typeParameters)
            val subst = GoSubstitution.of(params, named.typeArgs)
            return origin.underlying().substitute(subst)
        }
        val rhs = spec.type ?: return GoUnknownType
        var t = typeOf(rhs)
        if (spec.isAlias) return t.underlying()
        // go/types typeDecl: a lone type parameter on the right (`type T[P any] P`) is an error; the type is invalid.
        if (t is GoTypeParamType) return GoUnknownType
        // `type T U` where U is named: the underlying type of U.
        if (t is GoNamedType) t = t.underlying()
        return t
    }

    override fun methodsOf(named: GoNamedType): List<GoMethod> {
        val spec = named.declaration
        if (spec.isAlias && spec.type?.let { typeOf(it) } is GoNamedType && spec.typeParameters == null) {
            return (typeOf(spec.type!!) as GoNamedType).methods
        }
        val file = spec.containingFile as? GoFile ?: return emptyList()
        val name = spec.name ?: return emptyList()
        val scope = packages.scopeOf(file)
        val declared = ArrayList(scope.methodsOf(name))
        // Methods declared through an alias of this type (`type A = T; func (A) m()`).
        for (alias in scope.allDeclarations()) {
            if (alias !is GoTypeSpec || !alias.isAlias || alias.typeParameters != null || alias === spec) continue
            val target = RecursionManager.doPreventingRecursion(alias, false) { alias.type?.let { typeOf(it) } } ?: continue
            if (target is GoNamedType && target.declaration == spec) alias.name?.let { declared += scope.methodsOf(it) }
        }
        val pkgPath = packages.packagePathOf(file)
        val methods = declared.mapNotNull { m -> methodOf(m, pkgPath) }
        val origin = named.origin ?: return methods
        val params = typeParams(spec.typeParameters)
        val subst = GoSubstitution.of(params, named.typeArgs)
        return methods.map { it.withSignature(it.signature.substitute(subst) as GoSignatureType) }
    }

    override fun boundOf(param: GoTypeParamType): GoType {
        val decl = param.declaration.parent as? GoTypeParameterDeclaration ?: return GoUnknownType
        val t = constraintType(decl.constraintElem)
        // `[S ~[]E]`, `[T int | string]`, `[T int]`: shorthand for an interface with that type set.
        // A defined non-interface type (`[CC Chan]`) is a single-term type set as well.
        return if (t is GoInterfaceType || t is GoTypeParamType || t is GoUnknownType || t is GoNamedType && t.underlying().let { it is GoInterfaceType || it is GoUnknownType }) t
        // A named term is wrapped in a union: embedded named types are read as interfaces.
        else GoInterfaceType(emptyList(), listOf(if (t is GoNamedType) GoUnionType(listOf(GoTerm(false, t))) else t), implicit = true)
    }

    override fun packagePathOf(named: GoNamedType): String? =
        (named.declaration.containingFile as? GoFile)?.let { packages.packagePathOf(it) }

    /** The method value of a method declaration, with receiver type parameters mapped to the type's parameters. */
    fun methodOf(m: GoMethodDeclaration, pkgPath: String?): GoMethod? {
        val name = m.name ?: return null
        return CachedValuesManager.getCachedValue(m) {
            val sig = signatureOf(m.signature, m.typeParameters, receiverOf(m))
            CachedValueProvider.Result.create(GoMethod(name, sig, m, m.isPointerReceiver, pkgPath), *dependencies(m))
        }
    }

    private fun receiverOf(m: GoMethodDeclaration): GoParam? {
        val r = m.receiver ?: return null
        val t = r.type?.let { typeOf(it) } ?: GoUnknownType
        return GoParam(r.name, t, r)
    }

    fun functionType(f: GoFunctionDeclaration): GoSignatureType = CachedValuesManager.getCachedValue(f) {
        CachedValueProvider.Result.create(signatureOf(f.signature, f.typeParameters, null), *dependencies(f))
    }

    fun signatureOf(sig: GoSignature?, typeParams: GoTypeParameters?, receiver: GoParam?): GoSignatureType {
        val params = ArrayList<GoParam>()
        var variadic = false
        sig?.parameters?.parameterDeclarationList?.forEach { d ->
            val isVariadic = d.isVariadic
            var t = d.type?.let { typeOf(it) } ?: GoUnknownType
            if (isVariadic) { t = GoSliceType(t); variadic = true }
            val names = d.paramDefinitionList
            if (names.isEmpty()) params += GoParam(null, t) else names.forEach { params += GoParam(it.name, t, it) }
        }
        val results = ArrayList<GoParam>()
        val res = sig?.result
        if (res != null) {
            val ps = res.parameters
            if (ps != null) {
                ps.parameterDeclarationList.forEach { d ->
                    val t = d.type?.let { typeOf(it) } ?: GoUnknownType
                    val names = d.paramDefinitionList
                    if (names.isEmpty()) results += GoParam(null, t) else names.forEach { results += GoParam(it.name, t, it) }
                }
            } else res.type?.let { results += GoParam(null, typeOf(it)) }
        }
        return GoSignatureType(params, results, variadic, typeParams(typeParams), receiver)
    }

    // --- type PSI -> GoType ---

    /** The semantic type denoted by a type node. Cached per node ([GoBodyCache]: the body store inside bodies). */
    fun typeOf(node: PsiType): GoType = GoBodyCache.cached(node, TYPE_NODE_KEY) {
        RecursionManager.doPreventingRecursion(node, true) { computeType(node) }
    } ?: GoUnknownType

    private fun computeType(node: PsiType): GoType = when (node) {
        is PsiParType -> node.type?.let(::typeOf) ?: GoUnknownType
        is PsiPointerType -> GoPointerType(node.type?.let(::typeOf) ?: GoUnknownType)
        is PsiArrayOrSliceType -> {
            val elem = node.type?.let(::typeOf) ?: GoUnknownType
            val stub = GoPsiUtil.stubOf<GoTypeStub>(node)
            if (stub != null) {
                // Stub detail: null = slice, "..." = literal-length array, otherwise the length expression text.
                when (val detail = stub.detail) {
                    null -> GoSliceType(elem)
                    "..." -> GoArrayType(elem, null)
                    else -> GoArrayType(elem, detail.trim().replace("_", "").toLongOrNull() ?: stubArrayLength(detail.trim(), node))
                }
            } else {
                val lengthExpr = GoPsiUtil.children(node, GoExpression::class.java).firstOrNull()
                when {
                    node.ellipsis != null -> GoArrayType(elem, null)
                    lengthExpr == null -> GoSliceType(elem)
                    else -> GoArrayType(elem, GoExpressionTyper.getInstance(project).constantOf(lengthExpr)?.toBigInteger()?.toLong())
                }
            }
        }
        is PsiMapType -> { val ts = node.typeList; GoMapType(ts.getOrNull(0)?.let(::typeOf) ?: GoUnknownType, ts.getOrNull(1)?.let(::typeOf) ?: GoUnknownType) }
        is PsiChannelType -> {
            val elem = node.type?.let(::typeOf) ?: GoUnknownType
            val stub = GoPsiUtil.stubOf<GoTypeStub>(node)
            val dir = if (stub != null) {
                when (stub.detail) { "<-chan" -> GoChanDir.RECV; "chan<-" -> GoChanDir.SEND; else -> GoChanDir.BOTH }
            } else {
                val arrow = node.arrow
                val chan = node.chan
                when {
                    arrow == null -> GoChanDir.BOTH
                    chan != null && arrow.textRange.startOffset < chan.textRange.startOffset -> GoChanDir.RECV
                    else -> GoChanDir.SEND
                }
            }
            GoChanType(elem, dir)
        }
        is PsiFunctionType -> signatureOf(node.signature, null, null)
        is PsiStructType -> structOf(node)
        is PsiInterfaceType -> interfaceOf(node)
        is GoTypeList -> GoTupleType(node.typeList.map(::typeOf))
        else -> {
            val ref = node.typeReferenceExpression
            if (ref != null) {
                val base = typeOfReference(ref)
                val args = node.typeArguments?.typeList?.map(::typeOf)
                if (args != null && base is GoNamedType) base.instantiate(args) else base
            } else GoUnknownType
        }
    }

    /** A stubbed array length that is a (possibly qualified) constant name: evaluated through the package scope. */
    private fun stubArrayLength(text: String, node: PsiType): Long? {
        val file = node.containingFile as? GoFile ?: return null
        val typer = GoExpressionTyper.getInstance(project)
        val m = Regex("""^([A-Za-z_][A-Za-z0-9_]*)(?:\.([A-Za-z_][A-Za-z0-9_]*))?$""").find(text) ?: return null
        val (first, second) = m.destructured
        val def: GoNamedElement? = if (second.isEmpty()) {
            packages.scopeOf(file).lookup(first).firstOrNull { it is io.github.golangsupport.lang.psi.GoConstDefinition }
        } else {
            val spec = file.imports.firstOrNull { GoScopes.importName(it) == first } ?: return null
            GoResolver.getInstance(project).resolveMember(spec, second).firstOrNull()?.element as? GoNamedElement
        }
        return (def as? io.github.golangsupport.lang.psi.GoConstDefinition)?.let { typer.constantValueOf(it)?.toBigInteger()?.toLong() }
    }

    /** The type named by a type reference, resolved through scopes. */
    fun typeOfReference(ref: GoTypeReferenceExpression): GoType {
        val mixin = ref as? GoTypeReferenceExpressionMixin
        val name = mixin?.referenceName ?: ref.identifier?.text ?: return GoUnknownType
        val target = GoResolver.getInstance(project).resolveTypeReference(ref) ?: return builtinType(name, mixin?.qualifierName)
        return typeOfDeclaration(target, ref)
    }

    /** `builtin.go` declarations: basic types map to [GoBasicType], `any` to the empty interface, `error`/`comparable` are named. */
    private fun builtinDeclarationType(spec: GoTypeSpec): GoType {
        val name = spec.name ?: return GoUnknownType
        GoBasicType.byName(name)?.let { return it }
        return when (name) {
            "any" -> ANY
            // builtin.go declares `type comparable interface{ comparable }`: self-referential, so it is modelled directly.
            "comparable" -> COMPARABLE
            else -> namedType(spec)
        }
    }

    /** `package unsafe`: `Pointer` is the basic unsafe pointer type; `ArbitraryType`/`IntegerType` stand for any type. */
    private fun isUnsafeDeclaration(spec: GoTypeSpec): Boolean = (spec.containingFile as? GoFile)?.let { it.packageName == "unsafe" && packages.packagePathOf(it) == "unsafe" } == true

    private fun unsafeType(spec: GoTypeSpec): GoType = when (spec.name) {
        "Pointer" -> GoBasicType.UNSAFE_POINTER
        else -> GoUnknownType
    }

    private fun builtinType(name: String, qualifier: String?): GoType {
        if (qualifier == "unsafe" && name == "Pointer") return GoBasicType.UNSAFE_POINTER
        if (qualifier == "C") return GoUnknownType
        if (qualifier != null) return GoUnknownType
        GoBasicType.byName(name)?.let { return it }
        return when (name) {
            "any" -> ANY
            "error" -> ERROR
            "comparable" -> COMPARABLE
            else -> GoUnknownType
        }
    }

    /** The type a declaration denotes when used in type position. */
    fun typeOfDeclaration(decl: PsiElement, place: PsiElement): GoType = when (decl) {
        is GoTypeParamDefinition -> typeParam(decl)
        is GoTypeSpec -> if (GoUniverse.isBuiltinDeclaration(decl)) builtinDeclarationType(decl) else if (isUnsafeDeclaration(decl)) unsafeType(decl) else namedType(decl)
        else -> GoScopes.receiverTypeParamOf(decl)?.let(::typeParam) ?: GoUnknownType
    }

    private fun structOf(node: PsiStructType): GoStructType {
        val file = node.containingFile as? GoFile
        val pkgPath = file?.let { packages.packagePathOf(it) }
        val fields = ArrayList<GoField>()
        for (d in node.fieldDeclarationList) {
            val tag = d.tag?.let { t -> GoPsiUtil.stubOf<io.github.golangsupport.lang.stubs.GoTagStub>(t)?.text ?: t.text }
            val anon = d.anonymousFieldDefinition
            if (anon != null) {
                val ref = anon.typeReferenceExpression
                var t: GoType = ref?.let(::typeOfReference) ?: GoUnknownType
                anon.typeArguments?.typeList?.map(::typeOf)?.let { args -> if (t is GoNamedType) t = (t as GoNamedType).instantiate(args) }
                if ((anon as? GoAnonymousFieldDefinitionMixin)?.isPointer == true) t = GoPointerType(t)
                fields += GoField(anon.name ?: "", t, true, tag, anon, pkgPath)
            } else {
                val t = d.type?.let(::typeOf) ?: GoUnknownType
                for (f in d.fieldDefinitionList) fields += GoField(f.name ?: "", t, false, tag, f, pkgPath)
            }
        }
        return GoStructType(fields)
    }

    private fun interfaceOf(node: PsiInterfaceType): GoInterfaceType {
        val file = node.containingFile as? GoFile
        val pkgPath = file?.let { packages.packagePathOf(it) }
        val methods = node.methodSpecList.mapNotNull { spec ->
            val name = spec.name ?: return@mapNotNull null
            GoMethod(name, signatureOf(spec.signature, null, null), spec, false, pkgPath)
        }
        val embedded = node.constraintElemList.map(::constraintType)
        return GoInterfaceType(methods, embedded)
    }

    /** `A | ~B`: a single plain type stays itself; otherwise a union. */
    fun constraintType(elem: GoConstraintElem?): GoType {
        if (elem == null) return GoUnknownType
        val terms = elem.constraintTermList
        if (terms.size == 1 && !terms[0].hasTilde) return terms[0].type?.let(::typeOf) ?: GoUnknownType
        return GoUnionType(terms.map { GoTerm(it.hasTilde, it.type?.let(::typeOf) ?: GoUnknownType) })
    }

    private fun dependencies(element: PsiElement): Array<Any> = trackers.dependencies(element)

    companion object {
        private val TYPE_NODE_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<GoType?>>("gopsi.typeNode")

        /** `any`. */
        val ANY = GoInterfaceType(emptyList(), emptyList())
        /** `comparable`: an interface with no methods marked by a sentinel term set is awkward; modelled as empty + flag by identity. */
        val COMPARABLE = GoInterfaceType(emptyList(), emptyList(), comparableMarker = true)
        /** `error`: `interface { Error() string }`. */
        val ERROR = GoInterfaceType(listOf(GoMethod("Error", GoSignatureType(emptyList(), listOf(GoParam(null, GoBasicType.STRING)), false), null)), emptyList())
    }
}
