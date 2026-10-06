package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.extapi.psi.StubBasedPsiElementBase
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.lang.stubs.GoConstSpecStub
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.recvStatement
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType

/**
 * Unqualified names visible at the caret, innermost scope first (an inner declaration hides an
 * outer one with the same name): block-local declarations before the caret, `if`/`for`/`switch`
 * init statements, range and type-switch variables, function and literal signatures (receiver,
 * parameters, results, type parameters, receiver type parameters), type-spec type parameters,
 * package-level declarations of every file of the package (stubs), imports and dot imports,
 * universe. The walk mirrors `GoScopes.resolveName`, enumerating instead of looking up.
 */
class GoScopeCandidates(private val context: GoCompletionContext) {
    private val semantics = context.semantics
    private val seen = HashSet<String>()

    /** What to collect: values (expressions) and/or types (type positions). */
    enum class Filter { ALL, TYPES }

    fun collect(place: PsiElement, filter: Filter, out: MutableList<GoCandidate>) {
        locals(place, filter, out)
        packageLevel(filter, out)
        imports(filter, out)
        universe(filter, out)
    }

    private fun accept(name: String?): Boolean =
        !name.isNullOrEmpty() && name != "_" && !name.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) && seen.add(name)

    // --- locals ---

    private fun locals(place: PsiElement, filter: Filter, out: MutableList<GoCandidate>) {
        walkLocals(place, receiverTypeParam = { name, def ->
            if (accept(name)) out += GoCandidate(name, GoCandidateKind.TYPE_PARAMETER, GoScopeLevel.PARAMETER, def, tailText = " type parameter")
        }) { e, level ->
            if (filter == Filter.TYPES && e !is GoTypeSpec && e !is GoTypeParamDefinition) return@walkLocals
            val name = e.name
            if (accept(name)) out += declarationCandidate(e, name!!, level)
        }
    }

    // --- package level ---

    private fun packageLevel(filter: Filter, out: MutableList<GoCandidate>) {
        val declarations = ArrayList<GoNamedElement>()
        // The copy's own declarations (current text) replace the original file's.
        val copy = context.file
        declarations += copy.types
        if (filter == Filter.ALL) {
            declarations += copy.functions
            declarations += copy.vars
            declarations += copy.consts
        }
        for (e in semantics.packageScope.allDeclarations()) {
            val file = e.containingFile
            if (file === context.originalFile || file === copy) continue
            if (filter == Filter.TYPES && e !is GoTypeSpec) continue
            declarations += e
        }
        for (e in declarations) {
            val name = e.name
            if (e is GoFunctionDeclaration && name == "init") continue
            if (!accept(name)) continue
            out += declarationCandidate(e, name!!, GoScopeLevel.PACKAGE)
        }
    }

    // --- imports ---

    private fun imports(filter: Filter, out: MutableList<GoCandidate>) {
        for (spec in semantics.imports) {
            if (spec.isBlank) continue
            if (spec.isDot) {
                val pkg = semantics.importedPackage(spec) ?: continue
                for (e in semantics.scopeOf(pkg).allDeclarations()) {
                    if (!e.isPublic()) continue
                    if (filter == Filter.TYPES && e !is GoTypeSpec) continue
                    val name = e.name
                    if (!accept(name)) continue
                    // GoLand's rows of another package's members: the import path after the parameters (`ToUpper(s string) strings`)
                    val base = declarationCandidate(e, name!!, GoScopeLevel.IMPORTED)
                    val tail = GoLookupElementFactory.foreignTail(base, spec.path)
                    out += base.also { it.tailSupplier = tail; it.tailText = null }
                }
                continue
            }
            val name = semantics.importName(spec)
            if (!accept(name)) continue
            out += GoCandidate(name, GoCandidateKind.PACKAGE, GoScopeLevel.IMPORTED, spec, tailText = " (${spec.path})")
        }
    }

    // --- universe ---

    private fun universe(filter: Filter, out: MutableList<GoCandidate>) {
        val project = context.file.project
        val declarations = GoUniverse.declarations(project)
        val types = GoUniverse.BASIC_TYPES + GoUniverse.SPECIAL_TYPES
        for (name in types.sorted()) {
            if (name == "comparable" && filter == Filter.TYPES && !context.inConstraint) continue
            if (!accept(name)) continue
            out += GoCandidate(name, GoCandidateKind.BUILTIN_TYPE, GoScopeLevel.UNIVERSE, declarations[name], tailText = " builtin")
        }
        if (filter == Filter.TYPES) return
        for (name in GoUniverse.CONSTANTS.sorted()) {
            if (name == "iota" && !context.inConstSpec) continue
            if (!accept(name)) continue
            val type = when (name) {
                "true", "false" -> GoBasicType.UNTYPED_BOOL
                "nil" -> GoBasicType.UNTYPED_NIL
                else -> GoBasicType.UNTYPED_INT
            }
            out += GoCandidate(name, GoCandidateKind.BUILTIN_CONSTANT, GoScopeLevel.UNIVERSE, declarations[name], valueType = type, bold = true)
        }
        for (name in GoUniverse.FUNCTIONS.sorted()) {
            if (!accept(name)) continue
            val (tail, result) = builtinTexts(BUILTIN_SIGNATURES[name])
            out += GoCandidate(name, GoCandidateKind.BUILTIN_FUNCTION, GoScopeLevel.UNIVERSE, declarations[name], tailText = tail, typeText = result)
        }
    }

    /** A candidate for a declaration; types and texts come from stubs only for files whose AST is not loaded. */
    fun declarationCandidate(e: GoNamedElement, name: String, level: Int): GoCandidate =
        declarationCandidate(e, name, level, context)

    companion object {
        /**
         * The local scopes of [place], innermost first (an inner declaration hides an outer one with the same name): block-local
         * declarations before [place] (latest first), `if`/`for`/`switch` init statements, range and type-switch variables, function
         * and literal signatures, type-spec type parameters. [visit] gets each declaration with its level, [receiverTypeParam] the
         * receiver type parameters of a method. Shared by completion and the intentions (`ide.intentions.GoScopeValues`).
         */
        fun walkLocals(place: PsiElement, receiverTypeParam: (String, GoTypeParamDefinition) -> Unit = { _, _ -> }, visit: (GoNamedElement, Int) -> Unit) {
            var child: PsiElement = place
            var parent: PsiElement? = place.parent
            val placeStart = place.textRange.startOffset
            // Inner scopes first; within a block the latest declaration before the caret wins.
            while (parent != null && parent !is PsiFile) {
                val scopeOut = ArrayList<Pair<GoNamedElement, Int>>()
                fun add(e: GoNamedElement?, level: Int) { if (e != null) scopeOut += e to level }
                when (parent) {
                    is GoBlock -> statementsReversed(parent.statementList, child, placeStart).forEach { add(it, GoScopeLevel.LOCAL) }
                    is GoExprCaseClause -> statementsReversed(parent.statementList, child, placeStart).forEach { add(it, GoScopeLevel.LOCAL) }
                    is GoTypeCaseClause -> {
                        statementsReversed(parent.statementList, child, placeStart).forEach { add(it, GoScopeLevel.LOCAL) }
                        if (child !== parent.type) add((parent.parent as? GoTypeSwitchStatement)?.guard?.varDefinition, GoScopeLevel.LOCAL)
                    }
                    is GoCommClause -> {
                        statementsReversed(parent.statementList, child, placeStart).forEach { add(it, GoScopeLevel.LOCAL) }
                        if (child !== parent.commCase) parent.recvStatement?.varDefinitionList?.forEach { add(it, GoScopeLevel.LOCAL) }
                    }
                    is GoIfStatement -> parent.initStatement?.takeIf { it !== child }?.let { GoPsiUtil.declarationsOf(it).forEach { d -> add(d, GoScopeLevel.LOCAL) } }
                    is GoForStatement -> {
                        parent.forClause?.let { fc -> if (child !== fc) fc.initStatement?.let { GoPsiUtil.declarationsOf(it).forEach { d -> add(d, GoScopeLevel.LOCAL) } } }
                        parent.rangeClause?.let { rc -> if (child !== rc) rc.varDefinitionList.forEach { add(it, GoScopeLevel.LOCAL) } }
                    }
                    is GoRangeClause -> if (child !== parent.expression) parent.varDefinitionList.forEach { add(it, GoScopeLevel.LOCAL) }
                    is GoForClause -> parent.initStatement?.takeIf { it !== child }?.let { GoPsiUtil.declarationsOf(it).forEach { d -> add(d, GoScopeLevel.LOCAL) } }
                    is GoExprSwitchStatement -> parent.initStatement?.takeIf { it !== child }?.let { GoPsiUtil.declarationsOf(it).forEach { d -> add(d, GoScopeLevel.LOCAL) } }
                    is GoTypeSwitchStatement -> parent.initStatement?.takeIf { it !== child }?.let { GoPsiUtil.declarationsOf(it).forEach { d -> add(d, GoScopeLevel.LOCAL) } }
                    is GoFunctionLit -> signature(parent.signature, null, null).forEach { add(it, GoScopeLevel.PARAMETER) }
                    is GoFunctionDeclaration -> signature(parent.signature, parent.typeParameters, null).forEach { add(it, GoScopeLevel.PARAMETER) }
                    is GoMethodDeclaration -> {
                        signature(parent.signature, parent.typeParameters, parent.receiver).forEach { add(it, GoScopeLevel.PARAMETER) }
                        parent.receiver?.let { receiver ->
                            GoScopes.receiverTypeArguments(receiver)?.forEach { (identifier, name) ->
                                val def = GoScopes.receiverTypeParamOf(identifier) ?: return@forEach
                                receiverTypeParam(name, def)
                            }
                        }
                    }
                    is GoTypeSpec -> parent.typeParameters?.let { GoScopes.typeParamDefinitions(it) }?.forEach { add(it, GoScopeLevel.PARAMETER) }
                    else -> {}
                }
                for ((e, level) in scopeOut) visit(e, level)
                child = parent
                parent = parent.parent
            }
        }

        /** Declarations of the statements before the caret, latest first (a later declaration hides an earlier one). */
        private fun statementsReversed(statements: List<GoStatement>, child: PsiElement, placeStart: Int): List<GoNamedElement> {
            val result = ArrayList<GoNamedElement>()
            for (s in statements) {
                if (s === child) {
                    if (s is GoTypeDeclaration) result += GoPsiUtil.declarationsOf(s)
                    break
                }
                if (s.textRange.startOffset >= placeStart) break
                result += GoPsiUtil.declarationsOf(s)
            }
            return result.asReversed()
        }

        private fun signature(signature: GoSignature?, typeParams: GoTypeParameters?, receiver: GoReceiver?): List<GoNamedElement> {
            val result = ArrayList<GoNamedElement>()
            if (receiver != null && receiver.name != null) result += receiver
            typeParams?.let { result += GoScopes.typeParamDefinitions(it) }
            signature?.parameters?.parameterDeclarationList?.forEach { result += it.paramDefinitionList }
            signature?.result?.parameters?.parameterDeclarationList?.forEach { result += it.paramDefinitionList }
            return result
        }

        /** Signatures of the builtin functions as documented in `builtin.go` (the checker types them specially). */
        val BUILTIN_SIGNATURES: Map<String, String> = mapOf(
            "append" to "(slice []Type, elems ...Type) []Type",
            "cap" to "(v Type) int",
            "clear" to "(t T)",
            "close" to "(c chan<- Type)",
            "complex" to "(r, i FloatType) ComplexType",
            "copy" to "(dst, src []Type) int",
            "delete" to "(m map[Type]Type1, key Type)",
            "imag" to "(c ComplexType) FloatType",
            "len" to "(v Type) int",
            "make" to "(t Type, size ...IntegerType) Type",
            "max" to "(x T, y ...T) T",
            "min" to "(x T, y ...T) T",
            "new" to "(Type) *Type",
            "panic" to "(v any)",
            "print" to "(args ...Type)",
            "println" to "(args ...Type)",
            "real" to "(c ComplexType) FloatType",
            "recover" to "() any",
        )

        /** A builtin's documented signature split as GoLand shows it: the parameters as the tail, the result as the type (`(v Type)` / `int`). */
        fun builtinTexts(signature: String?): Pair<String?, String?> {
            if (signature == null) return null to null
            var depth = 0
            for ((i, c) in signature.withIndex()) {
                if (c == '(') depth++
                if (c == ')' && --depth == 0) return signature.substring(0, i + 1) to signature.substring(i + 1).trim().ifEmpty { null }
            }
            return signature to null
        }

        /** Longest value text shown in a constant's row; longer expressions are cut with `…`. */
        private const val MAX_VALUE = 40

        /**
         * The expression of constant [def] as written (`10`, `iota`, `1 << iota`), whitespace collapsed; for a row without values the
         * one of the row it repeats. Read from the stubs of the const specs, so the AST of another file is not loaded.
         */
        fun constValueText(def: GoConstDefinition): String? {
            val spec = def.parent as? GoConstSpec ?: return null
            val index = spec.constDefinitionList.indexOf(def)
            val specs = (spec.parent as? GoConstDeclaration)?.constSpecList ?: listOf(spec)
            var i = specs.indexOf(spec)
            while (i >= 0) {
                val values = valuesOf(specs[i])
                if (values.isNotEmpty()) return values.getOrNull(index)?.let(::shorten)
                if (specs[i].type != null) return null
                i--
            }
            return null
        }

        private fun valuesOf(spec: GoConstSpec): List<String> =
            ((spec as? StubBasedPsiElementBase<*>)?.greenStub as? GoConstSpecStub)?.values ?: spec.expressionList.map { it.text }

        private fun shorten(text: String): String {
            val flat = text.replace(Regex("\\s+"), " ")
            return if (flat.length <= MAX_VALUE) flat else flat.take(MAX_VALUE - 1) + "…"
        }

        fun declarationCandidate(e: GoNamedElement, name: String, level: Int, context: GoCompletionContext): GoCandidate {
            val semantics = context.semantics
            return when (e) {
                is GoFunctionDeclaration -> {
                    val sig = semantics.declarationType(e) as? GoSignatureType
                    // GoLand: `classify(x any)  string` (parameters as the tail, results as the type)
                    GoCandidate(
                        name, GoCandidateKind.FUNCTION, level, e, valueType = sig,
                        tailSupplier = sig?.let { { GoLookupElementFactory.paramsTail(it) } }, typeSupplier = sig?.let { { GoLookupElementFactory.resultText(it) } },
                    )
                }
                is GoMethodDeclaration -> {
                    val sig = semantics.declarationType(e) as? GoSignatureType
                    val owner = e.receiverTypeName?.let { if (e.isPointerReceiver) "*$it" else it }
                    GoCandidate(
                        name, GoCandidateKind.METHOD, level, e, valueType = sig,
                        tailSupplier = sig?.let { { GoLookupElementFactory.paramsTail(it) + GoLookupElementFactory.ownerTail(owner) } },
                        typeSupplier = sig?.let { { GoLookupElementFactory.resultText(it) } },
                    )
                }
                is GoTypeSpec -> GoCandidate(name, GoCandidateKind.TYPE, level, e, tailSupplier = { " " + GoLookupElementFactory.typeKind(e) })
                is GoTypeParamDefinition -> GoCandidate(name, GoCandidateKind.TYPE_PARAMETER, level, e, tailText = " type parameter")
                is GoConstDefinition -> {
                    val typed = (e.parent as? GoConstSpec)?.type != null
                    val type = if (typed || GoLookupElementFactory.astAvailable(e, context)) semantics.declarationType(e) else null
                    // as GoLand shows them: `MaxItems = 10  untyped int`, `Info = iota  Level` (a repeated row shows the expression it repeats)
                    GoCandidate(
                        name, GoCandidateKind.CONSTANT, level, e, valueType = type,
                        tailSupplier = { constValueText(e)?.let { " = $it" } }, typeSupplier = type?.let { { GoLookupElementFactory.typeText(it) } },
                    )
                }
                is GoVarDefinition -> {
                    val typed = (e.parent as? GoVarSpec)?.type != null
                    val type = if (typed || GoLookupElementFactory.astAvailable(e, context)) semantics.declarationType(e) else null
                    val kind = if (level == GoScopeLevel.LOCAL) GoCandidateKind.LOCAL else GoCandidateKind.VARIABLE
                    // GoLand: `c  Circle` (the type in the type column)
                    GoCandidate(name, kind, level, e, valueType = type, typeSupplier = type?.let { { GoLookupElementFactory.typeText(it) } })
                }
                is GoParamDefinition, is GoReceiver -> {
                    val type = semantics.declarationType(e)
                    GoCandidate(name, GoCandidateKind.PARAMETER, level, e, valueType = type, typeSupplier = { GoLookupElementFactory.typeText(type) })
                }
                else -> GoCandidate(name, GoCandidateKind.VARIABLE, level, e)
            }
        }
    }
}
