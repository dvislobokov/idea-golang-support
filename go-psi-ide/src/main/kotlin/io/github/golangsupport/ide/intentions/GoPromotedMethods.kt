package io.github.golangsupport.ide.intentions

import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoStructType

/**
 * A method a struct gets from an embedded field: [name] reached through the embedded field [field] (its first step), declared by
 * [owner]; [signature] is `(params) results` as the struct's file writes the types, [imports] what that needs.
 */
class GoPromotedMethod(val name: String, val field: String, val owner: String, val signature: String, val imports: Collection<String>)

/** What Override Methods can write for a struct: its receiver convention and the promoted methods it does not declare itself. */
class GoPromotedMethods(val typeName: String, val receiver: String, val pointer: Boolean, val methods: List<GoPromotedMethod>) {

    companion object {
        /**
         * The promoted methods of the package-level, non-generic struct of [spec] (embedded structs and interfaces, pointers or not, of any
         * package), the ones it declares itself left out, and so are unexported methods of another package, which it could not override.
         * Null when [spec] is no such struct. Read action, smart mode.
         */
        fun of(spec: GoTypeSpec): GoPromotedMethods? {
            if (spec.typeParameters != null || (spec.parent as? GoTypeDeclaration)?.parent !is GoFile) return null
            val file = spec.containingFile as? GoFile ?: return null
            val service = GoSemanticService.getInstance(spec.project)
            val named = service.declarationType(spec) as? GoNamedType ?: return null
            if (named.underlying() !is GoStructType) return null
            val own = named.methods.map { it.name }.toSet()
            val ownPath = GoPackageModel.getInstance(spec.project).packagePathOf(file)
            val methods = service.methodsOf(GoPointerType(named)).filter { it.name !in own && (it.isExported || it.pkgPath == null || it.pkgPath == ownPath) }.mapNotNull { m ->
                val selection = service.lookupFieldOrMethod(GoPointerType(named), m.name, file) as? GoLookup.Selection.Method ?: return@mapNotNull null
                val field = selection.path.firstOrNull()?.name ?: return@mapNotNull null
                val source = GoSourceText(file)
                val signature = GoCreateText.signature(source, selection.method.signature, selection.method.declaration)
                val owner = (selection.receiver as? GoPointerType)?.elem?.let { (it as? GoNamedType)?.name } ?: (selection.receiver as? GoNamedType)?.name ?: field
                GoPromotedMethod(m.name, field, owner, signature, source.imports.toList())
            }.sortedWith(compareBy({ it.field }, { it.name }))
            val receiver = GoCreateText.receiverOf(named)
            return GoPromotedMethods(named.name, receiver.name, receiver.pointer, methods)
        }
    }
}
