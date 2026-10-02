package io.github.golangsupport.semantic.scope

import com.intellij.openapi.project.Project
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.types.GoBasicType

/**
 * The universe scope. Names resolve to the declarations in `$GOROOT/src/builtin/builtin.go` (for
 * navigation and documentation); their types are computed specially by the inference engine.
 */
object GoUniverse {
    val BASIC_TYPES: Set<String> = setOf(
        "bool", "byte", "rune", "string", "int", "int8", "int16", "int32", "int64",
        "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "float32", "float64", "complex64", "complex128",
    )
    val SPECIAL_TYPES: Set<String> = setOf("any", "error", "comparable")
    val CONSTANTS: Set<String> = setOf("true", "false", "iota", "nil")
    val FUNCTIONS: Set<String> = setOf(
        "append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make", "max", "min",
        "new", "panic", "print", "println", "real", "recover",
    )
    val ALL: Set<String> = BASIC_TYPES + SPECIAL_TYPES + CONSTANTS + FUNCTIONS

    fun isBuiltin(name: String): Boolean = name in ALL

    fun basicType(name: String): GoBasicType? = GoBasicType.byName(name)

    /** True when [element] is declared in the `builtin` package of the toolchain's GOROOT. */
    fun isBuiltinDeclaration(element: GoNamedElement): Boolean {
        val file = element.containingFile as? GoFile ?: return false
        return file.packageName == "builtin" && file.name == "builtin.go"
    }

    /** The declarations of `builtin.go` by name, or empty when no GOROOT is configured. */
    fun declarations(project: Project): Map<String, GoNamedElement> {
        val file = builtinFile(project) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(file) {
            val map = HashMap<String, GoNamedElement>()
            file.types.forEach { t -> t.name?.let { map[it] = t } }
            file.functions.forEach { f -> f.name?.let { map[it] = f } }
            file.consts.forEach { c -> c.name?.let { map[it] = c } }
            file.vars.forEach { v -> v.name?.let { map[it] = v } }
            CachedValueProvider.Result.create(map, *GoTrackers.getInstance(project).packageDependencies(file))
        }
    }

    fun declaration(project: Project, name: String): GoNamedElement? = if (isBuiltin(name)) declarations(project)[name] else null

    fun builtinFile(project: Project): GoFile? {
        val toolchain = GoToolchainProvider.getInstance().toolchainFor(project) ?: return null
        val src = toolchain.gorootSrc ?: return null
        val dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(src.resolve("builtin")) ?: return null
        val pkg = GoPackageResolver.getInstance(project).packageOf(dir) ?: return null
        val vf = pkg.goFiles.firstOrNull { it.name == "builtin.go" } ?: return null
        return com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as? GoFile
    }
}
