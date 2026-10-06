package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * The values of a `return` from the PSI: the result types of the function around come from `GoSemanticService.enclosingResultTypes`, each value is a
 * variable in scope of exactly that type (the nearest; `err` first for `error`) or the zero value of the type. Inside
 * `if err != nil {` the values are the zero values and the checked error: what is returned there is the failure, not what was computed.
 */
object GoReturnValues {

    /** The one-line values (`0, "", err`) and, for an error that is a variable and a file that imports `fmt`, the same with the error wrapped. */
    class Values(val plain: String, val wrapped: String?)

    /**
     * The values for the `return` being typed at [position] (a leaf of the completion copy is fine): null when the PSI cannot tell (no
     * go-psi file, no function around, dumb mode); [NONE] when the function returns less than two values.
     */
    fun forReturn(position: PsiElement): Values? {
        val file = position.containingFile as? GoFile ?: return null
        if (DumbService.isDumb(file.project)) return null
        val statement = PsiTreeUtil.getParentOfType(position, GoReturnStatement::class.java) ?: return null
        val service = GoSemanticService.getInstance(file.project)
        val results = service.enclosingResultTypes(statement) ?: return null
        if (results.size < 2) return NONE
        val checked = checkedError(statement)
        val locals = if (checked != null) emptyList() else localVariables(statement)
        var errorVariable: String? = null
        val values = results.map { type ->
            when {
                isError(type) -> (checked ?: nearest(locals, type, service, preferred = "err"))?.also { errorVariable = it } ?: "nil"
                else -> nearest(locals, type, service, preferred = null) ?: zeroValue(type, file)
            }
        }
        val plain = values.joinToString(", ")
        val fmt = errorVariable?.let { importName(file, "fmt") } ?: return Values(plain, null)
        val message = failure(statement, checked != null) ?: "failed"
        val wrapped = values.map { if (it == errorVariable) "$fmt.Errorf(\"$message: %w\", $it)" else it }.joinToString(", ")
        return Values(plain, wrapped)
    }

    /**
     * `return err` / `return nil, 0, err` for a statement start at [position] (a leaf of the completion copy is fine), as GoLand's first row:
     * only inside a function that returns an error, with an error variable named `err` in scope; the other results are zero values.
     */
    fun errorReturn(position: PsiElement): String? {
        val file = position.containingFile as? GoFile ?: return null
        if (DumbService.isDumb(file.project)) return null
        if (PsiTreeUtil.getParentOfType(position, GoBlock::class.java) == null) return null
        val service = GoSemanticService.getInstance(file.project)
        val results = service.enclosingResultTypes(position)?.takeIf { it.isNotEmpty() } ?: return null
        if (results.none(::isError)) return null
        val err = localVariables(position).firstOrNull { it.name == "err" && isError(service.declarationType(it)) } ?: return null
        return "return " + results.joinToString(", ") { if (isError(it)) err.name!! else zeroValue(it, file) }
    }

    /** Nothing to offer: the function returns one value or none. */
    val NONE = Values("", null)

    /**
     * The function around [element] for [GoIdioms]: its parameters with their types written as in the file, its results with their zero
     * values; null outside functions or when the PSI cannot tell.
     */
    fun function(element: PsiElement): GoIdioms.Function? {
        val file = element.containingFile as? GoFile ?: return null
        if (DumbService.isDumb(file.project)) return null
        val owner = GoPsiUtil.functionOwner(element) ?: return null
        val service = GoSemanticService.getInstance(file.project)
        val signature = when (owner) {
            is GoFunctionOrMethodDeclaration -> service.declarationType(owner) as? GoSignatureType
            is GoFunctionLit -> service.typeOf(owner) as? GoSignatureType
            else -> null
        } ?: return null
        val parameters = signature.params.map { GoIdioms.Parameter(it.name?.takeIf { n -> n.isNotEmpty() && n != "_" }, typeText(it.type, file)) }
        val results = signature.results.map { r ->
            val error = isError(r.type)
            GoIdioms.Parameter(r.name?.takeIf { it.isNotEmpty() && it != "_" }, if (error) "error" else typeText(r.type, file), if (error) "nil" else zeroValue(r.type, file))
        }
        val name = (owner as? GoFunctionOrMethodDeclaration)?.name
        return GoIdioms.Function(name, parameters, results, isMain = owner is GoFunctionDeclaration && name == "main" && file.packageName == "main")
    }

    // --- variables in scope ---

    /**
     * The variables visible at [place], innermost scope first and, within a block, the latest declaration first: locals declared before
     * [place], `if`/`for`/`switch` init statements, range variables, parameters, results and the receiver of the functions around.
     */
    fun localVariables(place: PsiElement): List<GoNamedElement> {
        val seen = HashSet<String>()
        val out = ArrayList<GoNamedElement>()
        fun add(e: GoNamedElement?) {
            if (e !is GoVarDefinition && e !is GoParamDefinition && e !is GoReceiver) return
            val name = e.name
            if (name.isNullOrEmpty() || name == "_" || name.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) || !seen.add(name)) return
            out += e
        }
        val placeStart = place.textRange.startOffset
        var child: PsiElement = place
        var parent: PsiElement? = place.parent
        while (parent != null && parent !is PsiFile) {
            when (parent) {
                is GoBlock -> before(parent.statementList, child, placeStart).forEach(::add)
                is GoExprCaseClause -> before(parent.statementList, child, placeStart).forEach(::add)
                is GoTypeCaseClause -> before(parent.statementList, child, placeStart).forEach(::add)
                is GoCommClause -> before(parent.statementList, child, placeStart).forEach(::add)
                is GoIfStatement -> parent.initStatement?.takeIf { it !== child }?.let { GoPsiUtil.declarationsOf(it).forEach(::add) }
                is GoForStatement -> {
                    parent.forClause?.let { fc -> if (child !== fc) fc.initStatement?.let { GoPsiUtil.declarationsOf(it).forEach(::add) } }
                    parent.rangeClause?.let { rc -> if (child !== rc) rc.varDefinitionList.forEach(::add) }
                }
                is GoExprSwitchStatement -> parent.initStatement?.takeIf { it !== child }?.let { GoPsiUtil.declarationsOf(it).forEach(::add) }
                is GoFunctionLit -> signature(parent.signature, null).forEach(::add)
                is GoFunctionDeclaration -> signature(parent.signature, null).forEach(::add)
                is GoMethodDeclaration -> signature(parent.signature, parent.receiver).forEach(::add)
                else -> {}
            }
            child = parent
            parent = parent.parent
        }
        return out
    }

    private fun before(statements: List<GoStatement>, child: PsiElement, placeStart: Int): List<GoNamedElement> {
        val result = ArrayList<GoNamedElement>()
        for (s in statements) {
            if (s === child || s.textRange.startOffset >= placeStart) break
            result += GoPsiUtil.declarationsOf(s)
        }
        return result.asReversed()
    }

    private fun signature(signature: GoSignature?, receiver: GoReceiver?): List<GoNamedElement> {
        val result = ArrayList<GoNamedElement>()
        if (receiver != null && receiver.name != null) result += receiver
        signature?.parameters?.parameterDeclarationList?.forEach { result += it.paramDefinitionList }
        signature?.result?.parameters?.parameterDeclarationList?.forEach { result += it.paramDefinitionList }
        return result
    }

    /** The nearest variable of exactly [type]; [preferred] (`err`) wins when it has the type. */
    private fun nearest(locals: List<GoNamedElement>, type: GoType, service: GoSemanticService, preferred: String?): String? {
        if (type is GoUnknownType) return null
        val typed = locals.filter { GoTypePredicates.identical(service.declarationType(it), type) }
        return (typed.firstOrNull { it.name == preferred } ?: typed.firstOrNull())?.name
    }

    /** The error of the `if err != nil {` whose block holds [statement] directly; null elsewhere. */
    private fun checkedError(statement: GoReturnStatement): String? {
        val block = statement.parent as? GoBlock ?: return null
        val check = block.parent as? GoIfStatement ?: return null
        val condition = GoPsiUtil.run { check.condition } as? GoBinaryExpr ?: return null
        if (GoPsiUtil.run { condition.operator } != GoTypes.NEQ || condition.right?.text != "nil") return null
        val error = condition.left as? GoReferenceExpression ?: return null
        if (error.expression != null) return null
        val type = GoSemanticService.getInstance(statement.project).typeOf(error)
        return error.identifier?.text?.takeIf { type is GoUnknownType || isError(type) }
    }

    /** What has failed, in words, for the message of a wrapped error: the call that gave the error. */
    private fun failure(statement: GoReturnStatement, inCheck: Boolean): String? {
        val check = if (inCheck) (statement.parent?.parent as? GoIfStatement) else null
        val failed = check?.let { GoPsiUtil.run { it.initStatement } ?: PsiTreeUtil.getPrevSiblingOfType(it, GoStatement::class.java) }
            ?: PsiTreeUtil.getPrevSiblingOfType(statement, GoStatement::class.java)
        return GoIdioms.failure(failed?.text?.lineSequence()?.firstOrNull()?.trim())
    }

    // --- types as Go source ---

    /** The predeclared `error` (the semantic layer may model it as the unnamed interface `interface{ Error() string }`). */
    fun isError(type: GoType): Boolean {
        if (type is GoNamedType) return type.name == "error" && (type.pkgPath == null || (type.declaration.containingFile as? GoFile)?.packageName == "builtin")
        return type is GoInterfaceType && type.embedded.isEmpty() && type.methods.singleOrNull()?.let { m ->
            m.name == "Error" && m.signature.params.isEmpty() && m.signature.results.singleOrNull()?.type == GoBasicType.STRING
        } == true
    }

    /** The zero value of [type] as Go source in [file]: `0`, `""`, `false`, `nil`, `T{}`, `[2]int{}`, `*new(T)` for a type parameter. */
    fun zeroValue(type: GoType, file: GoFile): String {
        if (isError(type)) return "nil"
        if (type is GoTypeParamType) return "*new(${type.name})"
        return when (val u = type.underlying()) {
            is GoBasicType -> when {
                u.kind.isBoolean -> "false"
                u.kind.isString -> "\"\""
                u.kind.isNumeric -> "0"
                else -> "nil"
            }
            is GoStructType, is GoArrayType -> typeText(type, file) + "{}"
            else -> "nil"
        }
    }

    /** [type] as written in [file]: named types of other packages qualified by their import name (the package name when not imported). */
    fun typeText(type: GoType, file: GoFile): String {
        if (isError(type)) return "error"
        val own = GoPsiUtil.originalFile(file).viewProvider.virtualFile.parent
        return GoTypeRenderer.render(type) { named ->
            val declared = named.declaration.containingFile?.viewProvider?.virtualFile?.parent
            val path = named.pkgPath
            when {
                path == null || declared == null || declared == own -> null
                else -> {
                    val spec = file.imports.firstOrNull { it.path == path && !it.isBlank }
                    when {
                        spec == null -> GoScopes.defaultImportName(path)
                        spec.isDot -> null
                        else -> GoScopes.importName(spec)
                    }
                }
            }
        }
    }

    /** The local name of the import of [path] in [file], null when it is not imported (or imported for side effects or with a dot). */
    fun importName(file: GoFile, path: String): String? =
        file.imports.firstOrNull { it.path == path && !it.isBlank && !it.isDot }?.let(GoScopes::importName)
}
