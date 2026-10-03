package io.github.golangsupport.ide.intentions

import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * Stubs of the methods a named type of the project lacks to implement an interface (the gopls `stubmethods` equivalent): the methods of
 * [GoTypePredicates.missingMethods] whose names the type does not have at all (a method with a pointer receiver or another signature is
 * not stubbed twice), written as the type's file spells types, bodies `panic("not implemented")`, the receiver named like the type's
 * methods. Used by the quick fix on "does not implement"; the host's Implement Interface (Ctrl+I) can call [missing].
 */
object GoImplementStubs {

    /** What to insert for [spec]: [stubs] after the last method of the type (at [edit]), [imports] for its file. */
    class Stubs(val file: GoFile, val stubs: List<String>, val imports: Collection<String>, val edit: GoEditPlan.Edit) {
        fun plan(text: String): GoCreatePlan = GoCreatePlan(file, listOf(edit), imports, text)
    }

    /**
     * The stub texts (`func (s *Store) Close() error {\n\tpanic("not implemented")\n}`) of the methods of [iface] that the type declared by
     * [typeSpec] lacks, with the receiver convention of Create method (a pointer when its methods have one, or for a struct without methods).
     * Empty when nothing is missing or the type cannot get methods here (generic, local, an interface, outside the project, or an unexported
     * method of another package is missing).
     */
    fun missing(typeSpec: GoTypeSpec, iface: GoInterfaceType): List<String> = compute(typeSpec, iface, null)?.stubs.orEmpty()

    /** [missing] with the edit and the imports; [pointer] forces the receiver kind (the fix of a value `T` needs value receivers). */
    fun compute(typeSpec: GoTypeSpec, iface: GoInterfaceType, pointer: Boolean?): Stubs? {
        val service = GoSemanticService.getInstance(typeSpec.project)
        val named = service.declarationType(typeSpec) as? GoNamedType ?: return null
        if (named.declaration.typeParameters != null || named.isInstantiated || named.underlying() is GoInterfaceType) return null
        if ((typeSpec.parent as? GoTypeDeclaration)?.parent !is GoFile) return null
        val file = typeSpec.containingFile as? GoFile ?: return null
        if (!GoCreateText.isProjectFile(file)) return null
        val ownPath = GoPackageModel.getInstance(file.project).packagePathOf(file)
        val missing = GoTypePredicates.missingMethods(GoPointerType(named), iface)
        if (missing.any { !it.isExported && it.pkgPath != null && it.pkgPath != ownPath }) return null
        val absent = missing.filter { service.lookupFieldOrMethod(GoPointerType(named), it.name, file) == null }.distinctBy { it.name }
        if (absent.isEmpty()) return null
        val source = GoSourceText(file)
        val receiver = GoCreateText.receiverOf(named)
        val signatures = absent.associateWith { GoCreateText.signature(source, it.signature, it.declaration) }
        // a parameter named like the receiver would shadow it
        val clash = Regex("""[(,] ?${Regex.escape(receiver.name)} """)
        val receiverName = if (signatures.values.any { clash.containsMatchIn(it) }) "recv" else receiver.name
        val star = if (pointer ?: receiver.pointer) "*" else ""
        val stubs = absent.map { m -> "func ($receiverName $star${named.name}) ${m.name}${signatures.getValue(m)} {\n${GoCreateText.BODY}\n}" }
        val edit = GoCreateText.afterMethods(typeSpec, stubs.joinToString("\n\n")) ?: return null
        return Stubs(file, stubs, source.imports, edit)
    }
}
