package io.github.golangsupport.semantic.scope

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.recvStatement

/**
 * Lexical scopes (spec "Declarations and scope"): universe < package < file < function <
 * block < nested blocks, with declaration-before-use for locals, `if`/`for`/`switch`/`select`
 * implicit blocks, type-switch bindings, receiver and function type parameters.
 *
 * [resolveName] returns the declarations of [name] visible at [place], innermost scope first,
 * stopping at the first scope that declares it. Package-level results may be several (one per
 * file for duplicate declarations, which the checker reports).
 */
object GoScopes {

    sealed class Target {
        abstract val element: PsiElement
        /** A declaration (local, package-level, imported member, builtin). */
        data class Declaration(override val element: GoNamedElement) : Target()
        /** An import name (`fmt` in `fmt.Println`). */
        data class Import(override val element: GoImportSpec) : Target()
        /** A receiver type parameter name (`T` in `func (r *R[T]) M()`), bound to the type's parameter. */
        data class ReceiverTypeParam(override val element: GoTypeParamDefinition, val receiverName: PsiElement) : Target()
    }

    fun resolveName(place: PsiElement, name: String): List<Target> {
        if (name == "_" || name.isEmpty()) return emptyList()
        var child: PsiElement = place
        var parent: PsiElement? = stubAwareParent(place)
        while (parent != null) {
            val found = declarationsIn(parent, child, place, name)
            if (found.isNotEmpty()) return found
            if (parent is PsiFile) break
            child = parent
            parent = stubAwareParent(parent)
        }
        return emptyList()
    }

    /**
     * The parent through the stub tree when the element has a green stub (even a "dangling" one
     * built without the index, for which the platform's `getParent` would load the AST); the
     * transparent containers skipped by the stub builder contribute no declarations.
     */
    fun stubAwareParent(element: PsiElement): PsiElement? {
        val stub = (element as? StubBasedPsiElementBase<*>)?.greenStub
        if (stub != null) return stub.parentStub?.psi ?: element.parent
        return element.parent
    }

    /** The declarations [scope] contributes for [name] as seen from [child]/[place]. */
    private fun declarationsIn(scope: PsiElement, child: PsiElement, place: PsiElement, name: String): List<Target> {
        val out = ArrayList<Target>(1)
        fun add(e: GoNamedElement?) { if (e != null && e.name == name) out += Target.Declaration(e) }
        fun addAll(list: List<GoNamedElement>) = list.forEach(::add)
        when (scope) {
            is GoBlock -> statementsBefore(statementsOf(scope) { scope.statementList }, child, place, name, ::addAll)
            is GoExprCaseClause -> statementsBefore(statementsOf(scope) { scope.statementList }, child, place, name, ::addAll)
            is GoTypeCaseClause -> {
                if (child !== scope.type) {
                    val guard = (scope.parent as? GoTypeSwitchStatement)?.guard
                    add(guard?.varDefinition)
                }
                statementsBefore(statementsOf(scope) { scope.statementList }, child, place, name, ::addAll)
            }
            is GoCommClause -> {
                if (child !== scope.commCase) scope.recvStatement?.varDefinitionList?.let(::addAll)
                statementsBefore(statementsOf(scope) { scope.statementList }, child, place, name, ::addAll)
            }
            is GoIfStatement -> {
                val init = scope.initStatement
                if (init != null && child !== init) addAll(GoPsiUtil.declarationsOf(init))
            }
            is GoForStatement -> {
                // Inside the clause itself GoForClause/GoRangeClause decide what is visible.
                scope.forClause?.let { fc -> if (child !== fc) fc.initStatement?.let { addAll(GoPsiUtil.declarationsOf(it)) } }
                scope.rangeClause?.let { rc -> if (child !== rc) addAll(rc.varDefinitionList) }
            }
            is GoRangeClause -> if (child !== scope.expression) addAll(scope.varDefinitionList)
            is GoForClause -> { val init = scope.initStatement; if (init != null && child !== init) addAll(GoPsiUtil.declarationsOf(init)) }
            is GoExprSwitchStatement -> { val init = scope.initStatement; if (init != null && child !== init) addAll(GoPsiUtil.declarationsOf(init)) }
            is GoTypeSwitchStatement -> { val init = scope.initStatement; if (init != null && child !== init) addAll(GoPsiUtil.declarationsOf(init)) }
            // Receiver, parameter and result names are scoped to the body; type parameters to the whole declaration.
            is GoFunctionLit -> if (child is GoBlock) signatureDeclarations(scope.signature, null, null, name, ::add)
            is GoFunctionDeclaration -> if (child is GoBlock) signatureDeclarations(scope.signature, scope.typeParameters, null, name, ::add) else scope.typeParameters?.let { tp -> typeParamDefinitions(tp).forEach(::add) }
            is GoMethodDeclaration -> {
                if (child is GoBlock) signatureDeclarations(scope.signature, scope.typeParameters, scope.receiver, name, ::add)
                else scope.typeParameters?.let { tp -> typeParamDefinitions(tp).forEach(::add) }
                if (out.isEmpty()) receiverTypeParam(scope, name)?.let { out += it }
            }
            is GoTypeSpec -> scope.typeParameters?.let { addAll(typeParamDefinitions(it)) }
            is GoFile -> fileScope(scope as GoFile, name, out)
            else -> {}
        }
        return out
    }

    /**
     * The statements of a block or case clause, cached in the body store ([GoBodyCache]). Large statement lists
     * (generated code: `ssagen/simdAMD64intrinsics.go` has one function of thousands of statements)
     * also get a name index of their declarations, so resolving a name does not walk every
     * preceding statement (that was quadratic per block).
     */
    private class StatementList(val statements: List<GoStatement>) {
        /** name -> (statement index, declaration), in statement order; only for large lists. */
        val byName: Map<String, List<Pair<Int, GoNamedElement>>>? =
            if (statements.size < INDEXED_STATEMENTS) null else HashMap<String, MutableList<Pair<Int, GoNamedElement>>>().also { map ->
                statements.forEachIndexed { i, s ->
                    for (d in GoPsiUtil.declarationsOf(s)) map.getOrPut(d.name ?: continue) { ArrayList(1) } += i to d
                }
            }
    }

    private const val INDEXED_STATEMENTS = 32

    private val STATEMENTS_KEY = com.intellij.openapi.util.Key.create<com.intellij.psi.util.CachedValue<StatementList>>("gopsi.scopeStatements")

    private inline fun statementsOf(scope: PsiElement, crossinline compute: () -> List<GoStatement>): StatementList =
        GoBodyCache.cached(scope, STATEMENTS_KEY) { StatementList(compute()) }

    private fun statementsBefore(list: StatementList, child: PsiElement, place: PsiElement, name: String, add: (List<GoNamedElement>) -> Unit) {
        val statements = list.statements
        val placeStart = place.textRange.startOffset
        val index = list.byName
        if (index != null) {
            // Statements are ordered by offset: the visible ones are those before the first statement
            // that is [child] or starts at/after the reference.
            var lo = 0
            var hi = statements.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (statements[mid].textRange.startOffset < placeStart) lo = mid + 1 else hi = mid
            }
            // `lo` statements start before the reference; the last of them may be [child] (or [child]
            // starts exactly at the reference and is statements[lo]).
            var cut = lo
            if (cut > 0 && statements[cut - 1] === child) cut--
            val visible = index[name]?.filter { it.first < cut }?.map { it.second }
            if (!visible.isNullOrEmpty()) add(visible)
            if (cut < statements.size && statements[cut] === child) childStatement(statements[cut], placeStart, name, add)
            return
        }
        for (s in statements) {
            if (s === child) {
                childStatement(s, placeStart, name, add)
                break
            }
            if (s.textRange.startOffset >= placeStart) break
            val decls = GoPsiUtil.declarationsOf(s)
            if (decls.isNotEmpty()) add(decls.filter { it.name == name })
        }
    }

    /**
     * The statement containing the reference: only local type declarations are visible inside
     * themselves (`type T struct{ next *T }`); in a `var (...)` / `const (...)` group the specs
     * before the one containing the reference are visible.
     */
    private fun childStatement(s: GoStatement, placeStart: Int, name: String, add: (List<GoNamedElement>) -> Unit) {
        when (s) {
            is GoTypeDeclaration -> add(GoPsiUtil.declarationsOf(s).filter { it.name == name })
            is GoVarDeclaration -> add(s.varSpecList.filter { it.textRange.endOffset <= placeStart }.flatMap { it.varDefinitionList }.filter { it.name == name })
            is GoConstDeclaration -> add(s.constSpecList.filter { it.textRange.endOffset <= placeStart }.flatMap { it.constDefinitionList }.filter { it.name == name })
            else -> {}
        }
    }

    private fun signatureDeclarations(signature: GoSignature?, typeParams: GoTypeParameters?, receiver: GoReceiver?, name: String, add: (GoNamedElement?) -> Unit) {
        if (receiver != null && receiver.name == name) add(receiver)
        typeParams?.let { tp -> typeParamDefinitions(tp).forEach(add) }
        signature?.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(add) }
        signature?.result?.parameters?.parameterDeclarationList?.forEach { d -> d.paramDefinitionList.forEach(add) }
    }

    fun typeParamDefinitions(tp: GoTypeParameters): List<GoTypeParamDefinition> =
        tp.typeParameterDeclarationList.flatMap { it.typeParamDefinitionList }

    /**
     * `func (r *R[K, V]) M()`: the receiver's type arguments declare type parameter names bound by
     * position to the type's own parameters.
     */
    private fun receiverTypeParam(method: GoMethodDeclaration, name: String): Target? {
        val receiver = method.receiver ?: return null
        val args = receiverTypeArguments(receiver) ?: return null
        val index = args.indexOfFirst { it.second == name }
        if (index < 0) return null
        val baseName = method.receiverTypeName ?: return null
        val file = method.containingFile as? GoFile ?: return null
        val spec = GoPackageModel.getInstance(method.project).scopeOf(file).lookupType(baseName) ?: return null
        val params = spec.typeParameters?.let(::typeParamDefinitions) ?: return null
        val def = params.getOrNull(index) ?: return null
        return Target.ReceiverTypeParam(def, args[index].first)
    }

    /** For an identifier used as a receiver type argument (`T` in `func (r *R[T]) M()`), the type parameter it binds. */
    fun receiverTypeParamOf(identifier: PsiElement): GoTypeParamDefinition? {
        val receiver = PsiTreeUtil.getParentOfType(identifier, GoReceiver::class.java) ?: return null
        val method = receiver.parent as? GoMethodDeclaration ?: return null
        val args = receiverTypeArguments(receiver) ?: return null
        val index = args.indexOfFirst { it.first == identifier }
        if (index < 0) return null
        val baseName = method.receiverTypeName ?: return null
        val file = method.containingFile as? GoFile ?: return null
        val spec = GoPackageModel.getInstance(method.project).scopeOf(file).lookupType(baseName) ?: return null
        return spec.typeParameters?.let(::typeParamDefinitions)?.getOrNull(index)
    }

    /** The identifiers used as receiver type arguments, in order (`(T, U)` for `*R[T, U]`). */
    fun receiverTypeArguments(receiver: GoReceiver): List<Pair<PsiElement, String>>? {
        var t: GoType? = receiver.type
        while (t is GoParType || t is GoPointerType) t = if (t is GoParType) t.type else (t as GoPointerType).type
        val typeArgs = (t as? GoType)?.typeArguments ?: return null
        return typeArgs.typeList.map { arg ->
            val ref = arg.typeReferenceExpression
            (ref?.identifier ?: arg) to (ref?.identifier?.text ?: "")
        }
    }

    private fun fileScope(file: GoFile, name: String, out: MutableList<Target>) {
        val project = file.project
        for (spec in file.imports) {
            if (spec.isDot || spec.isBlank) continue
            if (importName(spec) == name) out += Target.Import(spec)
        }
        if (out.isNotEmpty()) return
        val model = GoPackageModel.getInstance(project)
        // `init` functions cannot be referred to (spec "Package initialization").
        model.scopeOf(file).lookup(name).filter { !(it is GoFunctionDeclaration && name == "init") }.forEach { out += Target.Declaration(it) }
        if (out.isNotEmpty()) return
        for (spec in file.imports) {
            if (!spec.isDot) continue
            val pkg = model.resolveImport(spec.path, file) ?: continue
            model.scopeOf(pkg).lookup(name).filter { it.isPublic() }.forEach { out += Target.Declaration(it) }
        }
        if (out.isNotEmpty()) return
        GoUniverse.declaration(project, name)?.let { out += Target.Declaration(it) }
    }

    /** The local name an import declares: the alias, or the resolved package's name, or the last path segment. */
    fun importName(spec: GoImportSpec): String {
        spec.alias?.takeIf { it != "." && it != "_" }?.let { return it }
        val file = spec.containingFile as? GoFile ?: return spec.name ?: ""
        return CachedValuesManager.getCachedValue(spec) {
            val model = GoPackageModel.getInstance(file.project)
            val resolved = model.resolveImport(spec.path, file)?.name
            CachedValueProvider.Result.create(resolved ?: defaultImportName(spec.path), *GoTrackers.getInstance(file.project).packageDependencies(file))
        }
    }

    /** The package name implied by an import path when the package cannot be read (`gopkg.in/yaml.v3` -> `yaml`). */
    fun defaultImportName(path: String): String {
        var last = path.substringAfterLast('/')
        // Major-version suffix directories (`/v2`) name the package after the previous segment.
        if (last.matches(Regex("v[0-9]+")) && path.contains('/')) last = path.removeSuffix("/$last").substringAfterLast('/')
        // `.v3` style (gopkg.in), `go-` prefix and `-go` suffix are conventional.
        last = last.substringBefore('.').removePrefix("go-").removeSuffix("-go").replace('-', '_')
        return last
    }

    /** Labels visible from [place]: all labeled statements of the enclosing function body. */
    fun resolveLabel(place: PsiElement, name: String): GoLabelDefinition? {
        val owner = GoPsiUtil.functionOwner(place) ?: return null
        val body = when (owner) {
            is GoFunctionOrMethodDeclaration -> owner.block
            is GoFunctionLit -> GoPsiUtil.run { owner.block }
            else -> null
        } ?: return null
        val labels = PsiTreeUtil.findChildrenOfType(body, GoLabelDefinition::class.java)
        return labels.firstOrNull { it.name == name && GoPsiUtil.functionOwner(it) === owner }
    }
}
