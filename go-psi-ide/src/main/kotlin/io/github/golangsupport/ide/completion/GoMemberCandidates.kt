package io.github.golangsupport.ide.completion

import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoField
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Candidates after `.`: package members (`fmt.`), method expressions (`T.`), fields and methods
 * of a value (`v.`) with promoted members, and members of a not yet imported package
 * (`strings.` without the import, added on insertion).
 */
class GoMemberCandidates(private val context: GoCompletionContext) {
    private val semantics = context.semantics

    fun collect(out: MutableList<GoCandidate>, typesOnly: Boolean) {
        val qualifier = context.qualifier ?: return
        if (qualifier is GoReferenceExpression && qualifier.expression == null) {
            val name = qualifier.identifier?.text ?: return
            val targets = semantics.resolveName(qualifier, name)
            when (val target = targets.firstOrNull()) {
                is GoScopes.Target.Import -> {
                    val pkg = semantics.importedPackage(target.element) ?: return
                    packageMembers(pkg, null, typesOnly, out)
                    return
                }
                is GoScopes.Target.Declaration -> {
                    val element = target.element
                    if (element is GoTypeSpec) {
                        if (!typesOnly) methodExpressions(element, out)
                        return
                    }
                }
                null -> {
                    unimportedPackageMembers(name, typesOnly, out)
                    return
                }
                else -> {}
            }
        }
        if (typesOnly) return
        valueMembers(qualifier, out)
    }

    // --- packages ---

    fun packageMembers(pkg: GoPackage, importPath: String?, typesOnly: Boolean, out: MutableList<GoCandidate>) {
        val scope = semantics.scopeOf(pkg)
        val seen = HashSet<String>()
        for (e in scope.allDeclarations()) {
            val name = e.name ?: continue
            if (!e.isPublic() || name == "init" || name == "_") continue
            if (typesOnly && e !is GoTypeSpec) continue
            if (!seen.add(name)) continue
            val base = GoScopeCandidates.declarationCandidate(e, name, GoScopeLevel.IMPORTED, context)
            // GoLand: `Clone(s string)  string` after `strings.`; a package not imported yet adds its path (`Marshal(v any) encoding/json`)
            out += GoCandidate(
                base.name, base.kind, if (importPath != null) GoScopeLevel.UNIMPORTED else GoScopeLevel.LOCAL, e,
                valueType = base.valueType, tailText = base.tailText.takeIf { importPath == null },
                tailSupplier = if (importPath != null) GoLookupElementFactory.foreignTail(base, importPath) else base.tailSupplier,
                typeSupplier = base.typeSupplier, typeText = base.typeText, importPath = importPath,
            )
        }
    }

    /** `strings.Bu<caret>` without `import "strings"`: the standard library first, then module packages with that name. */
    private fun unimportedPackageMembers(name: String, typesOnly: Boolean, out: MutableList<GoCandidate>) {
        if (semantics.imports.any { !it.isBlank && !it.isDot && semantics.importName(it) == name }) return
        val project = context.file.project
        val entry = GoImportPaths.all(project, context.originalFile.virtualFile).firstOrNull { it.name == name } ?: return
        val pkg = semantics.resolveImportPath(entry.path) ?: return
        if (pkg.name != null && pkg.name != name) return
        packageMembers(pkg, entry.path, typesOnly, out)
    }

    // --- method expressions ---

    private fun methodExpressions(spec: GoTypeSpec, out: MutableList<GoCandidate>) {
        val type = semantics.service.declarationType(spec)
        for (m in GoLookup.methodSet(type)) {
            if (!visible(m.isExported, m.pkgPath)) continue
            out += methodCandidate(m, depth = 0)
        }
    }

    // --- values ---

    private fun valueMembers(qualifier: GoExpression, out: MutableList<GoCandidate>) {
        val type = semantics.qualifierType
        if (type is GoUnknownType) return
        val seen = HashSet<String>()
        // Fields (with promotion depth) first, then methods.
        for (entry in fieldEntries(type)) {
            if (!seen.add(entry.field.name)) continue
            out += fieldCandidate(entry, GoCandidateKind.FIELD)
        }
        val methodSetType = if (type !is GoPointerType && type.underlying() !is GoInterfaceType && type !is GoTypeParamType && addressable(qualifier)) {
            GoPointerType(type)
        } else {
            type
        }
        for (m in GoLookup.methodSet(methodSetType)) {
            if (!visible(m.isExported, m.pkgPath)) continue
            if (!seen.add(m.name)) continue
            out += methodCandidate(m, depth = 0)
        }
    }

    /** A field (or struct literal key) row as GoLand shows it: `created → Base  time.Time`; an embedded field has no owner (`Base  Base`). */
    fun fieldCandidate(entry: FieldEntry, kind: GoCandidateKind, level: Int = GoScopeLevel.LOCAL + entry.depth): GoCandidate {
        val field = entry.field
        val owner = entry.owner.takeIf { !field.embedded }
        return GoCandidate(
            field.name, kind, level, field.declaration, valueType = field.type,
            tailText = GoLookupElementFactory.ownerTail(owner).ifEmpty { null }, typeSupplier = { GoLookupElementFactory.typeText(field.type) },
        )
    }

    /** Unused keys of a struct literal of [literalType] (named, pointer or plain struct): fields with promotion and their owners. */
    fun structKeys(literalType: GoType, used: Set<String>, out: MutableList<GoCandidate>) {
        for (entry in fieldEntries(literalType)) {
            if (entry.field.name in used) continue
            out += fieldCandidate(entry, GoCandidateKind.STRUCT_KEY)
        }
    }

    /** A method row as GoLand shows it: `Area() → *Square  float64`, `Name() → interface {...}  string`. */
    fun methodCandidate(m: GoMethod, depth: Int, level: Int = GoScopeLevel.LOCAL + depth, lookupString: String = m.name, lookupStrings: Collection<String> = emptyList()): GoCandidate {
        val owner = methodOwner(m)
        return GoCandidate(
            m.name, GoCandidateKind.METHOD, level, m.declaration, valueType = m.signature,
            tailSupplier = { GoLookupElementFactory.paramsTail(m.signature) + GoLookupElementFactory.ownerTail(owner) },
            typeSupplier = { GoLookupElementFactory.resultText(m.signature) }, lookupString = lookupString, lookupStrings = lookupStrings,
        )
    }

    /** One field with its promotion depth and the name of the struct type declaring it (null for an anonymous struct). */
    class FieldEntry(val field: GoField, val depth: Int, val owner: String?)

    /** Visible fields of [type] by promotion depth (a shallower field hides deeper ones; same-depth duplicates are ambiguous and dropped). */
    fun fields(type: GoType): List<Pair<GoField, Int>> = fieldEntries(type).map { it.field to it.depth }

    /** [fields] with the declaring type of each field (the owner GoLand shows after `→`). */
    fun fieldEntries(type: GoType): List<FieldEntry> {
        val result = ArrayList<FieldEntry>()
        val root = if (type is GoTypeParamType) type.coreType ?: type else type
        var level = listOf(namedName(root) to GoCompletionSemantics.derefUnderlying(root))
        val seenNamed = HashSet<GoType>()
        val names = HashSet<String>()
        var depth = 0
        while (level.isNotEmpty() && depth < 8) {
            val next = ArrayList<Pair<String?, GoType>>()
            val levelFields = ArrayList<Pair<GoField, String?>>()
            for ((owner, t) in level) {
                val struct = t as? GoStructType ?: continue
                for (f in struct.fields) {
                    if (visible(f.isExported, f.pkgPath)) levelFields += f to owner
                    if (f.embedded) {
                        val ft = if (f.type is GoPointerType) (f.type as GoPointerType).elem else f.type
                        if (ft is GoNamedType && !seenNamed.add(ft)) continue
                        next += namedName(ft) to ft.underlying()
                    }
                }
            }
            val counts = levelFields.groupingBy { it.first.name }.eachCount()
            for ((f, owner) in levelFields) {
                if (f.name in names) continue
                if ((counts[f.name] ?: 0) > 1) { names += f.name; continue }
                names += f.name
                result += FieldEntry(f, depth, owner)
            }
            level = next
            depth++
        }
        return result
    }

    private fun namedName(type: GoType): String? = when (type) {
        is GoPointerType -> namedName(type.elem)
        is GoNamedType -> type.name
        else -> null
    }

    companion object {
        /**
         * The receiver GoLand shows after `→`: `Base`, `*Square` (from the stub of the method declaration), `interface {...}` for a method
         * of an interface (GoLand writes it so even for a named interface).
         */
        fun methodOwner(m: GoMethod): String? = when (val d = m.declaration) {
            is GoMethodDeclaration -> d.receiverTypeName?.let { if (d.isPointerReceiver) "*$it" else it }
            is GoMethodSpec -> "interface {...}"
            else -> null
        }
    }

    fun visible(exported: Boolean, memberPkg: String?): Boolean =
        exported || memberPkg == null || semantics.packagePath == null || memberPkg == semantics.packagePath

    /** Variables, fields of addressable operands, pointer indirections and slice elements are addressable. */
    private fun addressable(expr: GoExpression): Boolean = when (expr) {
        is GoReferenceExpression -> {
            if (expr.expression == null) {
                val name = expr.identifier?.text
                val target = name?.let { semantics.resolveName(expr, it).firstOrNull() as? GoScopes.Target.Declaration }?.element
                target is GoVarDefinition || target is GoParamDefinition || target is GoReceiver
            } else {
                val q = expr.expression!!
                semantics.typeOf(q).let { it is GoPointerType || it.underlying() is GoPointerType } || addressable(q)
            }
        }
        is GoIndexOrSliceExpr -> true
        is GoParenthesesExpr -> expr.children.filterIsInstance<GoExpression>().firstOrNull()?.let(::addressable) ?: false
        is GoUnaryExpr -> io.github.golangsupport.semantic.psi.GoPsiUtil.run { expr.operator } == GoTypes.MUL
        else -> false
    }
}
