package io.github.golangsupport.lang

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSendStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.semantic.types.GoUnknownType

/** A variable, parameter or receiver in scope, with its type. */
class GoInlineVariable(val name: String, val element: GoNamedElement, val type: GoType) {
    val isParameter: Boolean get() = element is GoParamDefinition || element is GoReceiver
    override fun toString(): String = "$name ${GoTypeRenderer.render(type)}"
}

/**
 * How a name is used below its declaration (section I of the catalogue), from the uses that resolve to it in the function.
 */
class GoInlineUses {
    /** Each `append(x, v)`: the type of `v` and the loop over a collection around the append (its ranged expression). */
    val appended = ArrayList<Pair<GoType, GoExpression?>>()

    /** `x[i] = v` with an integer `i`: the type of `v` and the ranged expression of the loop around. */
    val indexAssigned = ArrayList<Pair<GoType, GoExpression?>>()

    /** `x[k] = v` with a key that is not an integer constant or variable: key and value types and the ranged expression around. */
    val keyAssigned = ArrayList<Triple<GoType, GoType, GoExpression?>>()

    /** `x[k] = v` with an integer key, for a map. */
    val intKeyAssigned = ArrayList<Triple<GoType, GoType, GoExpression?>>()

    /** `x[k]` read: the key types. */
    val keyRead = ArrayList<GoType>()

    /** `x[k]++`, `x[k] += n`: key and value types. */
    val counted = ArrayList<Pair<GoType, GoType>>()

    /** `copy(x, s)`: `s`. */
    var copiedFrom: GoExpression? = null

    /** `x.Name...`: the names selected on it. */
    val selected = LinkedHashSet<String>()

    /** `x <- v`: the type of `v`, whether in a goroutine, and the ranged expression of the loop the goroutine is started in. */
    val sends = ArrayList<Triple<GoType, Boolean, GoExpression?>>()

    /** `f(x)` / `f(&x)`: the types of the parameters. */
    val passedAs = ArrayList<GoType>()

    /** `&x` anywhere. */
    var addressTaken = false

    /** The types of the results it is returned as. */
    val returnedAs = ArrayList<GoType>()

    /** `x += v`, `x -= v`: the types of `v`. */
    val accumulated = ArrayList<GoType>()

    /** `x++`, `x--`. */
    var incremented = false

    /** `<-x`. */
    var received = false

    /** Any use in an index or an arithmetic expression with an integer. */
    var usedAsNumber = false

    var count = 0
}

/** The copy of the file with the slot filled, and what the rules ask of it. */
class GoInlinePlace private constructor(
    val file: GoFile, val original: GoFile, val text: CharSequence, val slot: GoInlineSuggestions.Slot, val unit: String, val indent: String,
    /** The leaf of the placeholder. */
    val leaf: PsiElement,
) {
    val project get() = file.project
    val semantic: GoSemanticService = GoSemanticService.getInstance(file.project)
    val imports = LinkedHashSet<String>()

    /**
     * The indent of the line of the slot: its own, or for a blank line without one (the editor keeps such a caret in virtual space) the
     * indent of the line above, a level deeper below what opens a block.
     */
    val blockIndent: String by lazy {
        if (indent.isNotEmpty()) return@lazy indent
        var end = slot.start
        while (end > 0 && text[end - 1] != '\n') end--
        while (end > 0) {
            var start = end - 1
            while (start > 0 && text[start - 1] != '\n') start--
            val line = text.subSequence(start, end - 1).toString().trimEnd()
            if (line.isNotBlank()) return@lazy line.takeWhile { it == ' ' || it == '\t' } + if (line.endsWith("{")) unit else ""
            end = start
        }
        ""
    }

    /** The placeholder as an expression (a reference); null where it is a type. */
    val expression: GoReferenceExpression? get() = leaf.parent as? GoReferenceExpression

    /** The function or literal around the slot; null at package level. */
    val owner: PsiElement? by lazy { GoPsiUtil.functionOwner(leaf) }

    val body: GoBlock? get() = when (val o = owner) {
        is GoFunctionOrMethodDeclaration -> o.block
        is GoFunctionLit -> PsiTreeUtil.getChildOfType(o, GoBlock::class.java)
        else -> null
    }

    /** The statement of the slot. */
    val statement: GoStatement? by lazy {
        var e: PsiElement? = leaf
        var found: GoStatement? = null
        while (e != null && e !is GoBlock && e !is PsiFile) {
            if (e is GoStatement) found = e
            e = e.parent
        }
        found
    }

    /** The statement before the slot in its block, null for the first. */
    val previousStatement: GoStatement? get() = statement?.let { PsiTreeUtil.getPrevSiblingOfType(it, GoStatement::class.java) }

    /** The variables in scope, innermost first (as [GoReturnValues.localVariables]), each with its type. */
    val variables: List<GoInlineVariable> by lazy {
        if (owner == null) emptyList()
        else GoReturnValues.localVariables(leaf).mapNotNull { e ->
            ProgressManager.checkCanceled()
            val name = e.name ?: return@mapNotNull null
            if (name == GoInlineSuggestions.PLACEHOLDER) return@mapNotNull null
            GoInlineVariable(name, e, typeOf(e))
        }
    }

    fun typeOf(element: GoNamedElement): GoType = runCatching { semantic.declarationType(element) }.getOrDefault(GoUnknownType)

    fun typeOf(expression: GoExpression): GoType = runCatching { semantic.typeOf(expression) }.getOrDefault(GoUnknownType)

    /** `context`, `T`, false for `context.Context` — by the type, or by the declaration when the package does not resolve. */
    fun standard(variable: GoInlineVariable): Triple<String, String, Boolean>? = GoScopeInputs.standardTypeOf(variable.element, semantic)

    fun isStandard(variable: GoInlineVariable, path: String, name: String, pointer: Boolean = false): Boolean =
        standard(variable)?.let { it.first == path && it.second == name && it.third == pointer } == true

    fun isStandard(type: GoType, path: String, name: String): Boolean = type is GoNamedType && type.name == name && type.pkgPath == path

    /** The local name of the package [path], recording its import when the file has none. */
    fun qualifier(path: String): String {
        GoReturnValues.importName(original, path)?.let { return it }
        GoReturnValues.importName(file, path)?.let { return it }
        imports += path
        return GoScopes.defaultImportName(path)
    }

    /** [type] as Go source in this file, the packages it names recorded for import; null when any part of it is unknown. */
    fun typeText(type: GoType): String? {
        val t = GoTypePredicates.defaultType(type)
        if (t is GoUnknownType || !GoTypePredicates.isKnown(t)) return null
        if (t is GoBasicType && t.isUntyped) return null
        if (GoReturnValues.isError(t)) return "error"
        val own = original.viewProvider.virtualFile?.parent
        return GoTypeRenderer.render(t) { named ->
            val declared = named.declaration.containingFile?.let { (it as? GoFile)?.let(GoPsiUtil::originalFile) ?: it }?.viewProvider?.virtualFile?.parent
            val path = named.pkgPath
            when {
                path == null || declared == null || declared == own -> null
                else -> {
                    val spec = original.imports.firstOrNull { it.path == path && !it.isBlank }
                    when {
                        spec == null -> qualifier(path)
                        spec.isDot -> null
                        else -> GoScopes.importName(spec)
                    }
                }
            }
        }
    }

    /** The functions of the package, by name: this file's own (the copy) and the other files of its directory. */
    val packageFunctions: Map<String, GoFunctionOrMethodDeclaration> by lazy {
        val result = LinkedHashMap<String, GoFunctionOrMethodDeclaration>()
        packageFiles().forEach { f -> f.functions.forEach { fn -> fn.name?.let { result.putIfAbsent(it, fn) } } }
        result
    }

    /** The types of the package, by name. */
    val packageTypes: Map<String, GoTypeSpec> by lazy {
        val result = LinkedHashMap<String, GoTypeSpec>()
        packageFiles().forEach { f -> f.types.forEach { spec -> spec.name?.let { result.putIfAbsent(it, spec) } } }
        result
    }

    private fun packageFiles(): List<GoFile> {
        val directory = original.containingDirectory ?: return listOf(file)
        val name = file.packageName
        val test = original.isTestFile
        return listOf(file) + directory.files.filterIsInstance<GoFile>().filter { it != original && it.packageName == name && (test || !it.isTestFile) }
    }

    /** The constants of the package, by name. */
    val packageConsts: Map<String, io.github.golangsupport.lang.psi.GoConstDefinition> by lazy {
        val result = LinkedHashMap<String, io.github.golangsupport.lang.psi.GoConstDefinition>()
        packageFiles().forEach { f -> f.consts.forEach { c -> c.name?.let { result.putIfAbsent(it, c) } } }
        result
    }

    /** The variables of the package, by name. */
    val packageVars: Map<String, GoVarDefinition> by lazy {
        val result = LinkedHashMap<String, GoVarDefinition>()
        packageFiles().forEach { f -> f.vars.forEach { v -> v.name?.let { result.putIfAbsent(it, v) } } }
        result
    }

    /** Whether the module of the file says `go 1.[minor]` or later; false when it is not known. */
    fun goVersionAtLeast(minor: Int): Boolean {
        val version = runCatching { semantic.packageOf(original)?.module?.goVersion }.getOrNull() ?: return false
        val parts = version.removePrefix("go").split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        return major > 1 || major == 1 && (parts.getOrNull(1)?.toIntOrNull() ?: 0) >= minor
    }

    /**
     * Whether [named] is declared in the package of the file. A name of the copy resolves to the declaration in the original file as
     * often as to its own, so the types are told by where they are declared, not by their identity.
     */
    fun isOwn(named: GoNamedType): Boolean {
        val file = named.declaration.containingFile ?: return false
        val declared = ((file as? GoFile)?.let(GoPsiUtil::originalFile) ?: file).viewProvider.virtualFile?.parent ?: return false
        return declared == original.viewProvider.virtualFile?.parent
    }

    /** [a] and [b] are the same type of this package (see [isOwn]), or identical. */
    fun same(a: GoType, b: GoType): Boolean =
        GoTypePredicates.identical(a, b) || a is GoNamedType && b is GoNamedType && a.name == b.name && a.typeArgs.isEmpty() && b.typeArgs.isEmpty() && isOwn(a) && isOwn(b)

    /** The struct type of [type] (through one pointer). */
    fun structOf(type: GoType): GoStructType? = ((if (type is GoPointerType) type.elem else type).underlying()) as? GoStructType

    /** The element type of a slice, an array or a pointer to an array. */
    fun elementOf(type: GoType): GoType? = when (val u = type.underlying()) {
        is GoSliceType -> u.elem
        is GoArrayType -> u.elem
        is GoPointerType -> (u.elem.underlying() as? GoArrayType)?.elem
        else -> null
    }

    fun isRangeable(type: GoType): Boolean = when (val u = type.underlying()) {
        is GoSliceType, is GoArrayType, is GoMapType -> true
        is GoChanType -> true
        is GoPointerType -> u.elem.underlying() is GoArrayType
        else -> false
    }

    fun isInteger(type: GoType): Boolean = (GoTypePredicates.defaultType(type).underlying() as? GoBasicType)?.kind?.isInteger == true

    fun isEmptyInterface(type: GoType): Boolean = (type.underlying() as? GoInterfaceType)?.let { it.methods.isEmpty() && it.embedded.isEmpty() } == true

    /** The definitions the statement of the slot declares (`x := |`, `var x = |`), in order. */
    val declared: List<GoNamedElement> by lazy {
        val s = statement ?: return@lazy emptyList()
        val short = s as? GoShortVarDeclaration ?: PsiTreeUtil.getChildOfType(s, GoShortVarDeclaration::class.java)
        short?.varDefinitionList?.let { return@lazy it }
        PsiTreeUtil.getParentOfType(leaf, GoVarSpec::class.java)?.varDefinitionList?.let { return@lazy it }
        emptyList()
    }

    /** The definition of the variable named [name] the slot assigns or declares (an assignment `x = |` resolves `x`). */
    fun definitionOf(name: String): GoNamedElement? {
        declared.firstOrNull { it.name == name }?.let { return it }
        val assignment = PsiTreeUtil.getParentOfType(leaf, GoAssignmentStatement::class.java) ?: return null
        val left = assignment.leftHandExprList?.expressionList.orEmpty().firstOrNull { it.text == name } as? GoReferenceExpression ?: return null
        return runCatching { semantic.resolve(left) }.getOrDefault(emptyList()).firstOrNull() as? GoNamedElement
    }

    /** The uses of [definition] below the statement of the slot, in the function. */
    fun usesOf(definition: GoNamedElement): GoInlineUses {
        val uses = GoInlineUses()
        val scope = body ?: return uses
        val name = definition.name ?: return uses
        val after = (statement ?: leaf).textRange.endOffset
        for (reference in PsiTreeUtil.findChildrenOfType(scope, GoReferenceExpression::class.java)) {
            ProgressManager.checkCanceled()
            if (reference.textRange.startOffset < after || reference.expression != null || reference.identifier?.text != name) continue
            val resolved = runCatching { semantic.resolve(reference) }.getOrDefault(emptyList())
            if (resolved.isNotEmpty() && definition !in resolved) continue
            uses.count++
            classify(reference, uses)
        }
        return uses
    }

    private fun classify(reference: GoReferenceExpression, uses: GoInlineUses) {
        var use: PsiElement = reference
        val parent = reference.parent
        if (parent is GoUnaryExpr && parent.and != null) {
            uses.addressTaken = true
            use = parent
        }
        when (val p = use.parent) {
            is GoArgumentList -> {
                val call = p.parent as? GoCallExpr ?: return
                val arguments = call.arguments
                val index = arguments.indexOf(use)
                val callee = (call.expression as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text
                when {
                    callee == "append" && index == 0 -> arguments.drop(1).filterIsInstance<GoExpression>().forEach { value ->
                        val type = typeOf(value).let { if (p.text.contains("...")) elementOf(it) ?: GoUnknownType else it }
                        uses.appended += type to rangedAround(call)
                    }
                    callee == "copy" && index == 0 -> uses.copiedFrom = arguments.getOrNull(1) as? GoExpression
                    callee == "len" || callee == "cap" || callee == "append" || callee == "close" || callee == "delete" -> {}
                    else -> runCatching { semantic.calleeSignature(call) }.getOrNull()?.let { signature ->
                        val parameter = if (signature.variadic && index >= signature.params.size - 1) signature.params.lastOrNull()?.type?.let { elementOf(it) }
                        else signature.params.getOrNull(index)?.type
                        parameter?.let(uses.passedAs::add)
                    }
                }
            }
            is GoIndexOrSliceExpr -> if (p.expression == use) indexUse(p, uses) else uses.usedAsNumber = true
            is GoReferenceExpression -> if (p.expression == use) p.identifier?.text?.let(uses.selected::add)
            is GoLeftHandExprList -> when (val statement = p.parent) {
                is GoSendStatement -> statement.expression?.let { value ->
                    val goroutine = PsiTreeUtil.getParentOfType(statement, GoGoStatement::class.java)
                    uses.sends += Triple(typeOf(value), goroutine != null, goroutine?.let(::rangedAround))
                }
                is GoIncDecStatement -> uses.incremented = true
                is GoAssignmentStatement -> if (statement.assignOp?.assign == null && p.expressionList.size == 1) {
                    statement.expressionList.singleOrNull()?.let { uses.accumulated += typeOf(it) }
                }
                else -> {}
            }
            is GoUnaryExpr -> if (p.arrow != null) uses.received = true
            is GoReturnStatement -> {
                val index = p.expressionList.indexOf(use)
                runCatching { semantic.enclosingResultTypes(p) }.getOrNull()?.getOrNull(index)?.let(uses.returnedAs::add)
            }
            is io.github.golangsupport.lang.psi.GoBinaryExpr -> {
                val other = if (p.left == use) p.right else p.left
                if (other != null && isInteger(typeOf(other))) uses.usedAsNumber = true
            }
            else -> {}
        }
    }

    private fun indexUse(index: GoIndexOrSliceExpr, uses: GoInlineUses) {
        val key = PsiTreeUtil.getChildrenOfTypeAsList(index, GoExpression::class.java).drop(1).firstOrNull() ?: return
        val keyType = typeOf(key)
        val around = rangedAround(index)
        val left = index.parent as? GoLeftHandExprList
        when (val statement = left?.parent) {
            is GoAssignmentStatement -> {
                val position = left.expressionList.indexOf(index)
                val value = statement.expressionList.getOrNull(position) ?: return
                val valueType = typeOf(value)
                if (statement.assignOp?.assign == null) {
                    uses.counted += keyType to valueType
                    return
                }
                when {
                    !isInteger(keyType) -> uses.keyAssigned += Triple(keyType, valueType, around)
                    else -> {
                        uses.indexAssigned += valueType to around
                        uses.intKeyAssigned += Triple(keyType, valueType, around)
                    }
                }
            }
            is GoIncDecStatement -> uses.counted += keyType to GoUnknownType
            else -> uses.keyRead += keyType
        }
    }

    /** The ranged expression of the nearest `for ... range` around [element] in the function. */
    fun rangedAround(element: PsiElement): GoExpression? {
        var e: PsiElement? = element.parent
        while (e != null && e != owner && e !is PsiFile) {
            if (e is GoForStatement) return e.rangeClause?.expression
            e = e.parent
        }
        return null
    }

    /** The ranged expression of a `range` clause directly. */
    fun rangedBy(clause: GoRangeClause?): GoExpression? = clause?.expression

    companion object {
        /** The copy of [file] from [text] with [slot] filled at [offset]; null when the slot is not an identifier there. */
        fun of(file: GoFile, text: CharSequence, offset: Int, slot: GoInlineSuggestions.Slot, unit: String): GoInlinePlace? {
            val filled = StringBuilder(text.length + 32).append(text, 0, slot.start).append(slot.insert).append(text, offset, text.length)
            val copy = PsiFileFactory.getInstance(file.project).createFileFromText(file.name, file.language, filled, false, false) as? GoFile ?: return null
            val original = GoPsiUtil.originalFile(file)
            (copy as PsiFileImpl).setOriginalFile(original)
            val at = slot.start + slot.lead.length
            val leaf = copy.findElementAt(at) ?: return null
            if (leaf.text != GoInlineSuggestions.PLACEHOLDER || leaf.textRange.startOffset != at) return null
            var lineStart = slot.start
            while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
            var end = lineStart
            while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
            val place = GoInlinePlace(copy, original, text, slot, unit, text.subSequence(lineStart, minOf(end, slot.start)).toString(), leaf)
            // large functions are not read: the suggestion is asked at every keystroke
            val body = place.body
            if (body != null && body.text.count { it == '\n' } > MAX_LINES) return null
            return place
        }

        private const val MAX_LINES = 400
    }
}
