package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParameters
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoParam
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnionType

/**
 * Edits of a declaration created from a usage: unlike [GoEditPlan] they may go to another file of the package (a method next to its type)
 * or to a file of another package of the project (`pkg.F(...)`); [imports] are added to [target].
 */
class GoCreatePlan(val target: GoFile, val edits: List<GoEditPlan.Edit>, val imports: Collection<String>, val text: String)

/** Applying a [GoCreatePlan]: edits from the end, then the imports of the target file. */
internal object GoCreateEdits {

    fun apply(plan: GoCreatePlan) {
        val file = plan.target
        val document = GoImportEdits.document(file) ?: return
        for (edit in plan.edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
        GoImportEdits.commit(file, document)
        if (plan.imports.isEmpty()) return
        for (path in plan.imports) GoImportInserter.addImport(file, document, path)
        GoImportEdits.commit(file, document)
    }

    /** The preview of [plan] for [file] (a copy of the editor's file): only when the plan writes into that very file, never into another one. */
    fun preview(plan: GoCreatePlan?, file: PsiFile): IntentionPreviewInfo {
        if (plan == null || plan.target != file) return IntentionPreviewInfo.EMPTY
        apply(plan)
        return IntentionPreviewInfo.DIFF
    }
}

/**
 * Text of the declarations created from usages (functions, methods, fields, variables, types; the stubs of missing methods): types as
 * the target file writes them ([GoSourceText]), `error` and `any` by name, bodies `panic("not implemented")`, parameter names from the
 * arguments or their types, receivers by the habit of the type's methods.
 */
internal object GoCreateText {
    const val BODY = "\tpanic(\"not implemented\")"

    /** `error` (the semantic layer models it as the unnamed `interface{ Error() string }`; rendered by name). */
    val ERROR: GoType = GoInterfaceType(listOf(GoMethod("Error", GoSignatureType(emptyList(), listOf(GoParam(null, GoBasicType.STRING)), false), null)), emptyList())

    private val KEYWORDS = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if",
        "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )

    /** [type] as [source]'s file writes it; null stands for `any`. A type the file cannot name (unknown, unexported in another package) is `any`. */
    fun type(source: GoSourceText, type: GoType?): String {
        if (type == null || !GoTypePredicates.isKnown(type) || mentions(type) { it is GoNamedType && !nameable(source, it) }) return "any"
        return source.type(type).replace("interface{Error() string}", "error").replace("interface{}", "any")
    }

    private fun nameable(source: GoSourceText, named: GoNamedType): Boolean {
        val name = named.name
        if (name.isEmpty() || name == "?") return false
        val file = named.declaration.containingFile as? GoFile ?: return false
        return file.packageName == "builtin" || source.isOwnPackage(file) || Character.isUpperCase(name[0])
    }

    /** The type of a value as a declaration takes it: the default type of an untyped constant, null (`any`) for `nil` and unknown types. */
    fun valueType(type: GoType?): GoType? {
        if (type == null || type is GoTupleType || !GoTypePredicates.isKnown(type)) return null
        if (type is GoBasicType && type.kind == GoBasicKind.UNTYPED_NIL) return null
        return GoTypePredicates.defaultType(type)
    }

    /** Whether [type] or a type it is built of satisfies [predicate] (named types by their type arguments, not their underlying types). */
    fun mentions(type: GoType, predicate: (GoType) -> Boolean): Boolean {
        if (predicate(type)) return true
        return when (type) {
            is GoPointerType -> mentions(type.elem, predicate)
            is GoSliceType -> mentions(type.elem, predicate)
            is GoArrayType -> mentions(type.elem, predicate)
            is GoChanType -> mentions(type.elem, predicate)
            is GoMapType -> mentions(type.key, predicate) || mentions(type.value, predicate)
            is GoTupleType -> type.types.any { mentions(it, predicate) }
            is GoSignatureType -> (type.params + type.results).any { mentions(it.type, predicate) }
            is GoStructType -> type.fields.any { mentions(it.type, predicate) }
            is GoNamedType -> type.typeArgs.any { mentions(it, predicate) }
            is GoUnionType -> type.terms.any { mentions(it.type, predicate) }
            is GoInterfaceType -> type.methods.any { mentions(it.signature, predicate) } || type.embedded.any { mentions(it, predicate) }
            else -> false
        }
    }

    fun mentionsTypeParam(type: GoType?): Boolean = type != null && mentions(type) { it is GoTypeParamType }

    // --- signatures of calls ---

    /**
     * `(a int, s string) (int, error)` for a call of a function to create: parameters from the arguments (a multi-value call spreads, `xs...`
     * makes it variadic), results from where the call stands ([resultTypes]). Null for type arguments and type parameters (not supported).
     */
    fun callSignature(call: GoCallExpr, source: GoSourceText, reserved: Set<String>): String? {
        val service = GoSemanticService.getInstance(call.project)
        val args = call.arguments
        if (args.any { it !is GoExpression }) return null
        val types = ArrayList<GoType?>()
        val origins = ArrayList<PsiElement?>()
        for (arg in args) {
            val t = service.typeOf(arg as GoExpression)
            if (t is GoTupleType) {
                if (args.size != 1) return null
                t.types.forEach { types += valueType(it); origins += null }
            } else {
                types += valueType(t)
                origins += arg
            }
        }
        val results = resultTypes(call)
        if ((types + results).any(::mentionsTypeParam)) return null
        val variadic = call.argumentList?.hasEllipsis == true && types.isNotEmpty()
        val names = paramNames(origins, types, reserved)
        val params = types.mapIndexed { i, t ->
            if (variadic && i == types.lastIndex) "${names[i]} ...${type(source, (t as? GoSliceType)?.elem)}" else "${names[i]} ${type(source, t)}"
        }
        return "(" + params.joinToString(", ") + ")" + results(source, results)
    }

    /** ` T`, ` (T1, T2)` or nothing. */
    fun results(source: GoSourceText, results: List<GoType?>): String = when (results.size) {
        0 -> ""
        1 -> " " + type(source, results[0])
        else -> " (" + results.joinToString(", ") { type(source, it) } + ")"
    }

    /**
     * The results a function called by [call] must have to fit where the call stands: none for a call statement (`go`, `defer`
     * included), one per variable of `a, err := f()` (`error` for one named `err`), the types of `x, y = f()`, else the expected type
     * at the call (a tuple for `return f()`), `any` when nothing is expected.
     */
    fun resultTypes(call: GoCallExpr): List<GoType?> {
        val service = GoSemanticService.getInstance(call.project)
        var e: GoExpression = call
        while (e.parent is GoParenthesesExpr) e = e.parent as GoExpression
        val parent = e.parent
        fun byName(name: String?): GoType? = if (name == "err") ERROR else null
        when {
            parent is GoLeftHandExprList && parent.parent is GoSimpleStatement && (parent.parent as GoSimpleStatement).statement == null -> return emptyList()
            parent is GoGoStatement || parent is GoDeferStatement -> return emptyList()
            parent is GoShortVarDeclaration && parent.expressionList.singleOrNull() == e -> return parent.varDefinitionList.map { byName(it.name) }
            parent is GoAssignmentStatement && parent.assignOp.assign != null && parent.expressionList.singleOrNull() == e -> {
                val lhs = parent.leftHandExprList.expressionList
                return lhs.map { if (it.text == "_") null else valueType(service.typeOf(it)) }
            }
            parent is GoVarSpec && parent.expressionList.singleOrNull() == e && parent.varDefinitionList.size > 1 -> {
                return parent.varDefinitionList.map { if (parent.type != null) valueType(service.declarationType(it)) else byName(it.name) }
            }
            parent is GoVarSpec && parent.expressionList.singleOrNull() == e && parent.type == null -> return listOf(byName(parent.varDefinitionList.singleOrNull()?.name))
        }
        val expected = service.expectedTypeAt(e) ?: return listOf(null)
        return if (expected is GoTupleType) expected.types.map(::valueType) else listOf(valueType(expected))
    }

    // --- names ---

    /** Parameter names: the argument's own name (`x`, `p.Name` → `name`) or one from its type; repeated ones numbered (`v1, v2`). */
    fun paramNames(origins: List<PsiElement?>, types: List<GoType?>, reserved: Set<String>): List<String> {
        val base = types.indices.map { i -> argumentName(origins[i]) ?: nameOf(types[i]) }.map { if (it in reserved) it + "1" else it }
        val counts = base.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return base.map { name -> if (counts.getValue(name) > 1) name + (seen.merge(name, 1, Int::plus)) else name }
    }

    private fun argumentName(arg: PsiElement?): String? {
        val ref = arg as? GoReferenceExpression ?: return null
        val name = ref.referenceName ?: return null
        if (name == "_" || name == "nil" || name == "true" || name == "false" || name == "iota") return null
        return if (ref.qualifier == null) name else safe(lowerFirst(name))
    }

    /** A name from [type]: `s` for a string, `i` for an int, `err`, `ctx`, `user` for `User` / `*User`, `m`, `ch`, `fn`, `v`. */
    fun nameOf(type: GoType?): String {
        if (type == null) return "v"
        if (type == ERROR || (type is GoInterfaceType && GoZeroValues.isError(type))) return "err"
        return when (type) {
            is GoNamedType -> when {
                type.name == "Context" && type.pkgPath == "context" -> "ctx"
                type.name == "error" -> "err"
                else -> safe(lowerFirst(type.name))
            }
            is GoBasicType -> when {
                type.kind.isString -> "s"
                type.kind.isBoolean -> "b"
                type.kind.isInteger -> "i"
                type.kind.isFloat -> "f"
                type.kind.isComplex -> "c"
                else -> "p"
            }
            is GoPointerType -> nameOf(type.elem)
            is GoSliceType -> if ((type.elem as? GoBasicType)?.kind == GoBasicKind.UINT8) "data" else (type.elem as? GoNamedType)?.let { safe(lowerFirst(it.name)) + "s" } ?: "values"
            is GoArrayType -> "values"
            is GoMapType -> "m"
            is GoChanType -> "ch"
            is GoSignatureType -> "fn"
            else -> "v"
        }
    }

    private fun lowerFirst(name: String): String {
        if (name.isEmpty()) return "v"
        // `HTTPClient` -> `httpClient`, `ID` -> `id`, `User` -> `user`
        var upper = 0
        while (upper < name.length && Character.isUpperCase(name[upper])) upper++
        return when {
            upper == 0 -> name
            upper == name.length -> name.lowercase()
            upper == 1 -> name[0].lowercaseChar() + name.substring(1)
            else -> name.substring(0, upper - 1).lowercase() + name.substring(upper - 1)
        }
    }

    private fun safe(name: String): String = if (name in KEYWORDS || name.isEmpty()) name.firstOrNull()?.toString() ?: "v" else name

    // --- receivers ---

    class Receiver(val name: String, val pointer: Boolean)

    /**
     * The receiver of a new method of [named]: the name its methods use (else the first letter of the type, lower case); a pointer when
     * a method of it has one, or when it has none and is a struct.
     */
    fun receiverOf(named: GoNamedType): Receiver {
        val declared = named.methods.mapNotNull { it.declaration as? GoMethodDeclaration }
        val name = declared.firstNotNullOfOrNull { m -> m.receiver?.identifier?.text?.takeIf { it != "_" } }
            ?: named.name.firstOrNull()?.lowercaseChar()?.toString()?.let(::safe) ?: "r"
        val pointer = if (declared.isEmpty()) named.underlying() is GoStructType else declared.any { it.isPointerReceiver }
        return Receiver(name, pointer)
    }

    /**
     * `(a int, b ...string) (n int, err error)` of a method stub: the names of the interface (from [declaration], a method spec, when the
     * signature has none, as for types read from stubs), else names from the parameter types; results stay unnamed when they are.
     */
    fun signature(source: GoSourceText, sig: GoSignatureType, declaration: PsiElement? = null): String {
        val signaturePsi = (declaration as? GoMethodSpec)?.signature
        val specParams = signaturePsi?.parameters?.let(::names)?.takeIf { it.size == sig.params.size }
        val specResults = signaturePsi?.result?.parameters?.let(::names)?.takeIf { it.size == sig.results.size }
        var paramNames: List<String?> = sig.params.mapIndexed { i, p -> p.name?.takeIf { it.isNotEmpty() } ?: specParams?.getOrNull(i) }
        if (paramNames.isNotEmpty() && paramNames.all { it == null }) paramNames = paramNames(paramNames.map { null }, sig.params.map { it.type }, emptySet())
        val resultNames = sig.results.mapIndexed { i, r -> r.name?.takeIf { it.isNotEmpty() } ?: specResults?.getOrNull(i) }.takeUnless { it.any { n -> n == null } }
        val params = sig.params.mapIndexed { i, p ->
            val t = if (sig.variadic && i == sig.params.lastIndex) "..." + type(source, (p.type as? GoSliceType)?.elem ?: p.type) else type(source, p.type)
            paramNames[i]?.let { "$it $t" } ?: t
        }
        val results = when {
            sig.results.isEmpty() -> ""
            sig.results.size == 1 && resultNames == null -> " " + type(source, sig.results[0].type)
            else -> " (" + sig.results.mapIndexed { i, r -> resultNames?.get(i)?.let { "$it ${type(source, r.type)}" } ?: type(source, r.type) }.joinToString(", ") + ")"
        }
        return "(" + params.joinToString(", ") + ")" + results
    }

    /** The names of a parameter list, one per parameter (null for an unnamed one). */
    private fun names(parameters: GoParameters): List<String?> =
        parameters.parameterDeclarationList.flatMap { d -> d.paramDefinitionList.ifEmpty { null }?.map { it.name } ?: listOf(null) }

    // --- placement ---

    /** [declaration] after the top-level declaration that contains [element], a blank line between. */
    fun afterTopLevel(element: PsiElement, declaration: String): GoEditPlan.Edit? {
        val file = element.containingFile as? GoFile ?: return null
        val top = PsiTreeUtil.findPrevParent(file, element)
        val end = top.textRange.endOffset
        return GoEditPlan.Edit(end, end, "\n\n$declaration")
    }

    /** [declaration] at the end of [file]. */
    fun atEnd(file: GoFile, declaration: String): GoEditPlan.Edit {
        val text = file.viewProvider.contents
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        return GoEditPlan.Edit(end, text.length, "\n\n$declaration\n")
    }

    /** [declaration] after the last method of [spec] in its file, or after the type declaration. Null for a type declared in a function. */
    fun afterMethods(spec: GoTypeSpec, declaration: String): GoEditPlan.Edit? {
        val file = spec.containingFile as? GoFile ?: return null
        val typeDeclaration = spec.parent as? GoTypeDeclaration ?: return null
        if (typeDeclaration.parent !is GoFile) return null
        val last = file.methods.filter { it.receiverTypeName == spec.name }.maxByOrNull { it.textRange.endOffset }
        val end = maxOf(last?.textRange?.endOffset ?: 0, typeDeclaration.textRange.endOffset)
        return GoEditPlan.Edit(end, end, "\n\n$declaration")
    }

    // --- targets ---

    /** Whether code may be created in [file]: a file of the project (content, or a package of the main module or a workspace member); its writability is asked for by the platform. */
    fun isProjectFile(file: PsiFile): Boolean {
        val vf = file.originalFile.virtualFile ?: return false
        if ((file as? GoFile)?.packageName == "builtin") return false
        if (ProjectFileIndex.getInstance(file.project).isInContent(vf)) return true
        val dir = vf.parent ?: return false
        val pkg = runCatching { GoPackageResolver.getInstance(file.project).packageOf(dir) }.getOrNull() ?: return false
        return !pkg.isStd && pkg.module?.let { it.isMain || it.isWorkspaceMember } == true
    }

    /**
     * The file to create the exported [name] in for `pkg.name` written in [file]: a non-test file of the imported package when that
     * package belongs to the project (the one named after the package first, else the first by name). Null for packages of the standard
     * library and the module cache, for unexported names and for the cgo pseudo package.
     */
    fun packageFile(file: GoFile, qualifier: GoReferenceExpression, name: String): GoFile? {
        if (name.isEmpty() || !Character.isUpperCase(name[0]) || qualifier.qualifier != null) return null
        val spec = GoSemanticService.getInstance(file.project).resolve(qualifier).firstOrNull() as? GoImportSpec ?: return null
        val pkg = GoPackageModel.getInstance(file.project).resolveImport(spec.path, file) ?: return null
        if (pkg.isStd) return null
        val psi = com.intellij.psi.PsiManager.getInstance(file.project)
        val files = pkg.goFiles.sortedBy { it.name }.mapNotNull { psi.findFile(it) as? GoFile }
        val target = files.firstOrNull { it.virtualFile.nameWithoutExtension == pkg.name } ?: files.firstOrNull() ?: return null
        return target.takeIf(::isProjectFile)
    }

    /** Whether the rendering into another package's file needed an import of [from]'s own package (an import cycle). */
    fun importsOwnPackage(source: GoSourceText, from: GoFile): Boolean {
        val own = GoPackageModel.getInstance(from.project).packagePathOf(from) ?: return false
        return own in source.imports
    }
}
