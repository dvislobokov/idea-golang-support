package io.github.golangsupport.catalogue

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubIndex
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarationPsi
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.stubs.GoParameterDeclarationStub
import io.github.golangsupport.lang.stubs.GoConstraintTermStub
import io.github.golangsupport.lang.stubs.GoNamedStub
import io.github.golangsupport.lang.stubs.GoTypeReferenceExpressionStub
import io.github.golangsupport.lang.stubs.GoTypeSpecStub
import io.github.golangsupport.lang.stubs.GoTypeStub
import io.github.golangsupport.lang.stubs.index.GoAllPublicNamesIndex
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * The packages of the project for the catalogue, from the stub index of exported package-level names ([GoAllPublicNamesIndex]):
 * the platform keeps it up to date, and nothing here loads the AST of a file. A directory is a package; its path is the one of its
 * module with the way from the go.mod to it. The standard library and the module cache are not in the stub indices (no library
 * roots), they stay with [GoCatalogueScanner].
 */
object GoProjectPackages {
    private val SKIPPED = Regex("/(vendor|testdata|node_modules)/")
    private const val MAX_SIGNATURE = 200

    /** Changes with every out-of-block change of a Go file of the project: whether the packages are to be put together again. */
    fun stamp(project: Project): Long = GoTrackers.getInstance(project).projectOutOfBlock.modificationCount

    /** The exported names of the project by package. Needs read access and the indexes (smart mode). */
    fun of(project: Project): List<GoPackageSymbols> {
        val scope = GlobalSearchScope.projectScope(project)
        val index = StubIndex.getInstance()
        val names = ArrayList<String>()
        index.processAllKeys(GoAllPublicNamesIndex.KEY, { names += it; true }, scope, null)
        val byDirectory = LinkedHashMap<VirtualFile, MutableMap<VirtualFile, MutableList<GoSymbol>>>()
        for (name in names) {
            ProgressManager.checkCanceled()
            index.processElements(GoAllPublicNamesIndex.KEY, name, project, scope, GoNamedElement::class.java) { element ->
                val file = element.containingFile?.virtualFile
                val directory = file?.parent
                if (directory != null && GoCatalogueScanner.isSource(file.name) && !SKIPPED.containsMatchIn(file.path)) symbolOf(element)?.let {
                    byDirectory.getOrPut(directory) { LinkedHashMap() }.getOrPut(file) { ArrayList() } += it
                }
                true
            }
        }
        val modules = GoModulesService.getInstance(project)
        val psi = PsiManager.getInstance(project)
        return byDirectory.mapNotNull { (directory, symbols) ->
            val importPath = modules.moduleOf(directory)?.importPath(directory) ?: return@mapNotNull null
            // every source file of the directory votes for the name of the package, the ones that export nothing too
            val files = directory.children.filter { !it.isDirectory && GoCatalogueScanner.isSource(it.name) }.mapNotNull { file ->
                val packageName = (psi.findFile(file) as? GoFile)?.packageName ?: return@mapNotNull null
                GoFileExports(packageName, symbols[file].orEmpty().sortedBy { it.name })
            }
            GoCatalogueScanner.merge(importPath, files)
        }.sortedBy { it.importPath }
    }

    /** A symbol of the catalogue from a stub-backed declaration; null for a method (a method is not imported by its name). */
    private fun symbolOf(element: GoNamedElement): GoSymbol? {
        val name = element.name ?: return null
        // vars and consts in the index are package-level by construction (no stubs in bodies): `kindOf` would climb the AST to check it
        val kind = when (element) {
            is GoVarDefinition -> GoDeclarationKind.VAR
            is GoConstDefinition -> GoDeclarationKind.CONST
            is GoFunctionDeclaration, is GoTypeSpec -> GoDeclarationPsi.kindOf(element)
            else -> null
        } ?: return null
        return GoSymbol(name, kind, GoStubTexts.signatureOf(element)?.take(MAX_SIGNATURE))
    }
}

/**
 * The text of a declaration's signature as the scanner of the catalogue gives it, rendered from the stub tree: `(id int) (*Order, error)`
 * for a function, the type for a type, a var or a const (`map[string]int`, `struct{...}`). The stubs keep names and the structure of types,
 * not the source text: spacing is the canonical one, and an array length is the text the stub keeps. When the element has no stub (its
 * file's AST is loaded), the source text is read instead, which costs nothing then.
 */
object GoStubTexts {
    private val WHITESPACE = Regex("""\s+""")

    fun signatureOf(element: PsiElement): String? {
        val stub = (element as? StubBasedPsiElementBase<*>)?.greenStub ?: return fromSource(element)
        return when (element) {
            is GoFunctionDeclaration -> stub.child(GoTypes.TYPE_PARAMETERS)?.let(::typeParameters).orEmpty() + (stub.child(GoTypes.SIGNATURE)?.let(::signature) ?: return null)
            is GoTypeSpec -> {
                val type = stub.childrenStubs.firstOrNull { it is GoTypeStub } ?: return null
                stub.child(GoTypes.TYPE_PARAMETERS)?.let { typeParameters(it) + " " }.orEmpty() + (if ((stub as? GoTypeSpecStub)?.isAlias == true) "= " else "") + typeText(type)
            }
            // the type is the spec's, after the names: `var a, b int`
            is GoVarDefinition, is GoConstDefinition -> stub.parentStub?.childrenStubs?.firstOrNull { it is GoTypeStub }?.let(::typeText)
            else -> null
        }
    }

    private fun fromSource(element: PsiElement): String? = when (element) {
        is GoFunctionDeclaration -> (element.typeParameters?.text.orEmpty() + (element.signature?.text ?: return null)).let(::normalize)
        is GoTypeSpec -> element.type?.text?.let { (if (element.isAlias) "= " else "") + normalize(it) }
        else -> null
    }

    private fun normalize(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun StubElement<*>.child(type: Any): StubElement<*>? = childrenStubs.firstOrNull { it.stubType === type }

    private fun StubElement<*>.types(): List<StubElement<*>> = childrenStubs.filter { it is GoTypeStub }

    fun signature(signature: StubElement<*>): String {
        val parameters = signature.child(GoTypes.PARAMETERS)?.let(::parameters) ?: "()"
        val result = signature.child(GoTypes.RESULT) ?: return parameters
        val inner = result.child(GoTypes.PARAMETERS)?.let(::parameters) ?: result.types().firstOrNull()?.let(::typeText) ?: return parameters
        return "$parameters $inner"
    }

    private fun parameters(parameters: StubElement<*>): String = parameters.childrenStubs.joinToString(", ", "(", ")") { declaration ->
        val names = declaration.childrenStubs.filter { it.stubType === GoTypes.PARAM_DEFINITION }.mapNotNull { (it as? GoNamedStub<*>)?.name }
        val variadic = if ((declaration as? GoParameterDeclarationStub)?.isVariadic == true) "..." else ""
        val type = declaration.types().firstOrNull()?.let(::typeText) ?: "?"
        (if (names.isEmpty()) "" else names.joinToString(", ", postfix = " ")) + variadic + type
    }

    private fun typeParameters(parameters: StubElement<*>): String = parameters.childrenStubs.joinToString(", ", "[", "]") { declaration ->
        val names = declaration.childrenStubs.filter { it.stubType === GoTypes.TYPE_PARAM_DEFINITION }.mapNotNull { (it as? GoNamedStub<*>)?.name }
        names.joinToString(", ") + (declaration.child(GoTypes.CONSTRAINT_ELEM)?.let { " " + constraint(it) } ?: "")
    }

    private fun constraint(element: StubElement<*>): String = element.childrenStubs.joinToString(" | ") { term ->
        (if ((term as? GoConstraintTermStub)?.hasTilde == true) "~" else "") + (term.types().firstOrNull()?.let(::typeText) ?: "?")
    }

    fun typeText(stub: StubElement<*>): String {
        val detail = (stub as? GoTypeStub)?.detail
        val inner = stub.types()
        fun first() = inner.firstOrNull()?.let(::typeText) ?: "?"
        return when (stub.stubType) {
            GoTypes.TYPE -> {
                val reference = stub.childrenStubs.firstNotNullOfOrNull { it as? GoTypeReferenceExpressionStub }?.qualifiedText ?: "?"
                reference + (stub.child(GoTypes.TYPE_ARGUMENTS)?.let { arguments -> arguments.types().joinToString(", ", "[", "]") { typeText(it) } } ?: "")
            }
            GoTypes.PAR_TYPE -> "(${first()})"
            GoTypes.ARRAY_OR_SLICE_TYPE -> "[${detail.orEmpty()}]${first()}"
            GoTypes.POINTER_TYPE -> "*${first()}"
            GoTypes.FUNCTION_TYPE -> "func" + (stub.child(GoTypes.SIGNATURE)?.let(::signature) ?: "()")
            GoTypes.MAP_TYPE -> "map[${inner.getOrNull(0)?.let(::typeText) ?: "?"}]${inner.getOrNull(1)?.let(::typeText) ?: "?"}"
            GoTypes.CHANNEL_TYPE -> "${detail ?: "chan"} ${first()}"
            GoTypes.STRUCT_TYPE -> if (stub.childrenStubs.isEmpty()) "struct{}" else "struct{...}"
            GoTypes.INTERFACE_TYPE -> if (stub.childrenStubs.isEmpty()) "interface{}" else "interface{...}"
            GoTypes.TYPE_LIST -> inner.joinToString(", ") { typeText(it) }
            else -> "?"
        }
    }
}
