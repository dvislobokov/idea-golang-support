package io.github.golangsupport.lang

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.recvStatement
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoUnknownType
import io.github.golangsupport.lang.psi.GoPointerType as GoPointerTypePsi
import io.github.golangsupport.lang.psi.GoType as GoTypePsi
import io.github.golangsupport.semantic.types.GoPointerType as GoPointerTypeSem

/**
 * What the PSI of go-psi says about a place in a function body, for the keyword templates ([GoKeywordTemplates]): the variables in
 * scope before the caret with what a template can do with them (range over, select on, close), and for the top of a file the methods
 * the types of the file have and the interfaces of the project. Types come from [GoSemanticService]; where a type of the standard
 * library does not resolve (no GOROOT), the declared type is read from the PSI: `ctx context.Context` with `import "context"`.
 * Null answers mean "the PSI cannot tell" (no enclosing function, dumb mode): the caller keeps what the lines above tell then.
 */
object GoScopeInputs {
    enum class Kind { SLICE, MAP, CHANNEL, CONTEXT, TESTING, TIMER }

    /**
     * A variable or parameter in scope. [receives] / [sends]: the directions of a channel. [madeHere]: declared in the function of the
     * caret with `make(...)`; [closed]: that function calls `close` on it. [typeName]: `T`, `B`, `F` of `*testing.X`, `Timer` / `Ticker`.
     */
    class Variable(
        val name: String, val kind: Kind, val receives: Boolean = false, val sends: Boolean = false,
        val madeHere: Boolean = false, val closed: Boolean = false, val typeName: String? = null,
    ) {
        override fun toString(): String = "$name: $kind"
    }

    /** The variables in scope at [offset], innermost first (a later declaration hides an earlier one); null outside a function body. */
    fun at(file: GoFile, offset: Int): List<Variable>? {
        if (DumbService.isDumb(file.project)) return null
        val place = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        if (GoPsiUtil.functionOwner(place) == null || place.parentOfType<GoBlock>() == null) return null
        val semantic = GoSemanticService.getInstance(file.project)
        val owner = GoPsiUtil.functionOwner(place)
        val seen = HashSet<String>()
        val result = ArrayList<Variable>()
        for (element in visible(place, offset)) {
            val name = element.name ?: continue
            // a hidden name stays hidden whatever its type: `items := 3` in a block shadows `items []T` of the function
            if (name == "_" || !seen.add(name)) continue
            classify(element, semantic, owner)?.let(result::add)
        }
        return result
    }

    /** The names of the variables and parameters in scope at [offset] around [place] (postfix templates make a new name unique against them). */
    fun visibleNames(place: PsiElement, offset: Int): List<String> = visible(place, offset).mapNotNull { it.name }

    /** The variables and parameters declared before [offset] in the scopes around [place], innermost first: the walk of `GoScopeCandidates.locals`. */
    private fun visible(place: PsiElement, offset: Int): List<GoNamedElement> {
        val result = ArrayList<GoNamedElement>()
        var child: PsiElement = place
        var parent: PsiElement? = place.parent
        while (parent != null && parent !is PsiFile) {
            ProgressManager.checkCanceled()
            when (parent) {
                is GoBlock -> result += before(parent.statementList, child, offset)
                is GoExprCaseClause -> result += before(parent.statementList, child, offset)
                is GoTypeCaseClause -> {
                    result += before(parent.statementList, child, offset)
                    if (child !== parent.type) (parent.parent as? GoTypeSwitchStatement)?.guard?.varDefinition?.let(result::add)
                }
                is GoCommClause -> {
                    result += before(parent.statementList, child, offset)
                    if (child !== parent.commCase) parent.recvStatement?.varDefinitionList?.let(result::addAll)
                }
                is GoIfStatement -> parent.initStatement?.takeIf { it !== child }?.let { result += GoPsiUtil.declarationsOf(it) }
                is GoForStatement -> {
                    parent.forClause?.takeIf { it !== child }?.initStatement?.let { result += GoPsiUtil.declarationsOf(it) }
                    parent.rangeClause?.takeIf { it !== child }?.let { result += it.varDefinitionList }
                }
                is GoRangeClause -> if (child !== parent.expression) result += parent.varDefinitionList
                is GoForClause -> parent.initStatement?.takeIf { it !== child }?.let { result += GoPsiUtil.declarationsOf(it) }
                is GoExprSwitchStatement -> parent.initStatement?.takeIf { it !== child }?.let { result += GoPsiUtil.declarationsOf(it) }
                is GoTypeSwitchStatement -> parent.initStatement?.takeIf { it !== child }?.let { result += GoPsiUtil.declarationsOf(it) }
                is GoFunctionLit -> result += parameters(parent.signature, null)
                is GoFunctionDeclaration -> return result + parameters(parent.signature, null)
                is GoMethodDeclaration -> return result + parameters(parent.signature, parent.receiver)
            }
            child = parent
            parent = parent.parent
        }
        return result
    }

    private fun before(statements: List<GoStatement>, child: PsiElement, offset: Int): List<GoNamedElement> {
        val result = ArrayList<GoNamedElement>()
        for (statement in statements) {
            if (statement === child || statement.textRange.startOffset >= offset) break
            result += GoPsiUtil.declarationsOf(statement).filter { it is GoVarDefinition }
        }
        return result.asReversed()
    }

    private fun parameters(signature: GoSignature?, receiver: GoReceiver?): List<GoNamedElement> = buildList {
        signature?.parameters?.parameterDeclarationList?.forEach { addAll(it.paramDefinitionList) }
        signature?.result?.parameters?.parameterDeclarationList?.forEach { addAll(it.paramDefinitionList) }
        if (receiver?.name != null) add(receiver)
    }

    /**
     * The type of a variable when it is a named type of a package: (package path, type name, whether a pointer to it) - `testing`, `T`,
     * true for `t *testing.T`. By the semantic layer, by the declaration when that type is unknown (the package does not resolve).
     */
    fun standardTypeOf(element: GoNamedElement, semantic: GoSemanticService = GoSemanticService.getInstance(element.project)): Triple<String, String, Boolean>? {
        val type = runCatching { semantic.declarationType(element) }.getOrDefault(GoUnknownType)
        return standardType(type) ?: if (type == GoUnknownType) declaredStandardType(element) else null
    }

    private fun classify(element: GoNamedElement, semantic: GoSemanticService, owner: PsiElement?): Variable? {
        val name = element.name ?: return null
        val type = runCatching { semantic.declarationType(element) }.getOrDefault(GoUnknownType)
        val standard = standardType(type) ?: if (type == GoUnknownType) declaredStandardType(element) else null
        // the types of the standard library a template knows by name; any other named type by what it is underneath (`type Items []Item`)
        if (standard != null) {
            val (path, typeName, pointer) = standard
            when {
                path == "context" && typeName == "Context" && !pointer -> return Variable(name, Kind.CONTEXT)
                path == "testing" && typeName in TESTING_TYPES && pointer -> return Variable(name, Kind.TESTING, typeName = typeName)
                path == "time" && (typeName == "Timer" || typeName == "Ticker") && pointer -> return Variable(name, Kind.TIMER, typeName = typeName)
            }
        }
        return when (val underlying = type.underlying()) {
            is GoSliceType, is GoArrayType -> Variable(name, Kind.SLICE)
            is GoPointerTypeSem -> if (underlying.elem.underlying() is GoArrayType) Variable(name, Kind.SLICE) else null
            is GoMapType -> Variable(name, Kind.MAP)
            is GoChanType -> {
                val made = madeHere(element, owner)
                Variable(name, Kind.CHANNEL, receives = underlying.dir != GoChanDir.SEND, sends = underlying.dir != GoChanDir.RECV, madeHere = made, closed = made && isClosed(element, owner, semantic))
            }
            else -> null
        }
    }

    private val TESTING_TYPES = setOf("T", "B", "F")

    /** `context.Context`, `*testing.T`: the package (path, or its name when the path is not known), the name of the type, and whether it is a pointer. */
    private fun standardType(type: GoType): Triple<String, String, Boolean>? {
        val pointer = type is GoPointerTypeSem
        val named = (if (type is GoPointerTypeSem) type.elem else type) as? GoNamedType ?: return null
        val path = named.pkgPath ?: (named.declaration.containingFile as? GoFile)?.packageName ?: return null
        return Triple(path, named.name, pointer)
    }

    /** The same from the declaration itself, `t *testing.T` with `import "testing"`: what is left when the package of the type does not resolve. */
    private fun declaredStandardType(element: GoNamedElement): Triple<String, String, Boolean>? {
        val declared: GoTypePsi = when (element) {
            is GoParamDefinition -> element.parentOfType<GoParameterDeclaration>()?.type
            is GoVarDefinition -> (element.parent as? GoVarSpec)?.type
            is GoReceiver -> element.type
            else -> null
        } ?: return null
        val pointer = declared is GoPointerTypePsi
        val reference = (if (declared is GoPointerTypePsi) declared.type else declared)?.typeReferenceExpression ?: return null
        val qualifier = reference.referenceExpression?.text ?: return null
        val name = reference.identifier?.text ?: return null
        val file = element.containingFile as? GoFile ?: return null
        val import = file.imports.firstOrNull { !it.isBlank && !it.isDot && (it.alias ?: GoScopes.defaultImportName(it.path)) == qualifier } ?: return null
        return Triple(import.path, name, pointer)
    }

    /** `ch := make(chan T)` or `var ch = make(chan T)` in the function of the caret. */
    private fun madeHere(element: GoNamedElement, owner: PsiElement?): Boolean {
        if (element !is GoVarDefinition || owner == null || GoPsiUtil.functionOwner(element) !== owner) return false
        val (definitions, values) = when (val parent = element.parent) {
            is GoShortVarDeclaration -> parent.varDefinitionList to parent.expressionList
            is GoVarSpec -> parent.varDefinitionList to parent.expressionList
            else -> return false
        }
        val value = values.getOrNull(definitions.indexOf(element)).takeIf { definitions.size == values.size } as? GoCallExpr ?: return false
        return (value.expression as? GoReferenceExpression)?.let { it.expression == null && it.identifier?.text == "make" } == true
    }

    /** Whether the function of the caret calls `close` on the variable anywhere: a second `close` would panic. */
    private fun isClosed(element: GoNamedElement, owner: PsiElement?, semantic: GoSemanticService): Boolean {
        val body = when (owner) {
            is GoFunctionOrMethodDeclaration -> owner.block
            is GoFunctionLit -> PsiTreeUtil.getChildOfType(owner, GoBlock::class.java)
            else -> null
        } ?: return false
        return PsiTreeUtil.findChildrenOfType(body, GoCallExpr::class.java).any { call ->
            val callee = call.expression as? GoReferenceExpression ?: return@any false
            if (callee.expression != null || callee.identifier?.text != "close") return@any false
            val argument = call.arguments.singleOrNull() as? GoReferenceExpression ?: return@any false
            argument.expression == null && argument.identifier?.text == element.name &&
                runCatching { semantic.resolve(argument) }.getOrDefault(emptyList()).let { it.isEmpty() || element in it }
        }
    }

    /** The methods of every type of [file] by the name of the type, its own and promoted ones, value and pointer receivers; null in dumb mode. */
    fun methodsByType(file: GoFile): Map<String, Set<String>>? {
        if (DumbService.isDumb(file.project)) return null
        val semantic = GoSemanticService.getInstance(file.project)
        val result = LinkedHashMap<String, Set<String>>()
        for (spec in file.types) {
            val name = spec.name ?: continue
            val type = runCatching { semantic.declarationType(spec) }.getOrNull() as? GoNamedType ?: continue
            if (type.underlying() is GoInterfaceType) continue
            val methods = runCatching { semantic.methodsOf(GoPointerTypeSem(type)) }.getOrDefault(emptyList()).mapTo(LinkedHashSet()) { it.name }
            if (methods.isNotEmpty()) result[name] = methods
        }
        return result
    }

    /**
     * The interfaces of the project ([GoProjectInterfaces]) that some type of [has] has begun to implement, with the name and the
     * signature of each method (embedded ones included, from the method set of the semantic layer); null in dumb mode.
     */
    fun interfaces(file: GoFile, has: Map<String, Set<String>>): List<GoKeywordTemplates.InterfaceInfo>? {
        val project = file.project
        if (DumbService.isDumb(project)) return null
        val semantic = GoSemanticService.getInstance(project)
        val result = ArrayList<GoKeywordTemplates.InterfaceInfo>()
        for (entry in GoProjectInterfaces.getInstance(project).entries()) {
            ProgressManager.checkCanceled()
            // by the names the index knows: an interface no type of the file has a method of is not read further
            if (!entry.embeds && has.values.none { methods -> entry.methods.any { it in methods } && !methods.containsAll(entry.methods) }) continue
            val psi = PsiManager.getInstance(project).findFile(entry.file) as? GoFile ?: continue
            val spec = psi.types.firstOrNull { it.name == entry.name } ?: continue
            val type = runCatching { semantic.declarationType(spec) }.getOrNull() ?: continue
            val methods = runCatching { semantic.methodsOf(type) }.getOrDefault(emptyList()).map { method ->
                method.name to ((method.declaration as? GoMethodSpec)?.signature?.text ?: semantic.render(method.signature).removePrefix("func"))
            }
            if (methods.isNotEmpty()) result += GoKeywordTemplates.InterfaceInfo(entry.name, methods)
        }
        return result.sortedBy { it.name }
    }
}
