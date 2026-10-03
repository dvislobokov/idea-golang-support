package io.github.golangsupport.ide.asm

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.stubs.index.GoFunctionIndex
import javax.swing.Icon

/**
 * A symbol of an assembly file split at its last middle dot: `·add` is (`""`, `add`), `runtime·morestack` is (`runtime`, `morestack`),
 * `internal∕bytealg·Index` is (`internal/bytealg`, `Index`). [nameOffset] is where [name] starts in the symbol text.
 */
data class GoAsmName(val pkg: String, val name: String, val nameOffset: Int) {
    /** The symbol belongs to the package [packageName] of its directory: no qualifier, or one whose last path element is that name. */
    fun inPackage(packageName: String?): Boolean = pkg.isEmpty() || packageName != null && pkg.substringAfterLast('/') == packageName

    companion object {
        /** `null` for a name without `·` or with nothing usable after it (`·(*T).M` lexes as `·` followed by operators). */
        fun parse(text: String): GoAsmName? {
            val dot = text.lastIndexOf('·')
            if (dot < 0) return null
            val name = text.substring(dot + 1)
            if (name.isEmpty() || !(name[0].isLetter() || name[0] == '_') || name.any { !(it.isLetterOrDigit() || it == '_') }) return null
            return GoAsmName(text.substring(0, dot).replace('∕', '/'), name, dot + 1)
        }
    }
}

/** A symbol of an assembly file (the token [GoAsmTokenTypes.SYMBOL]); the one after `TEXT` defines a function of the package. */
class GoAsmSymbol(node: ASTNode) : ASTWrapperPsiElement(node) {
    val asmName: GoAsmName? get() = GoAsmName.parse(text)

    /** `TEXT ·add(SB), …`: the symbol is the first operand of a TEXT directive. */
    val isTextDefinition: Boolean
        get() = PsiTreeUtil.skipWhitespacesAndCommentsBackward(this)?.let { it.node.elementType === GoAsmTokenTypes.DIRECTIVE && it.text == "TEXT" } == true

    override fun getReference(): PsiReference? {
        val name = asmName ?: return null
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project)) return null
        return GoAsmSymbolReference(this, TextRange(name.nameOffset, textLength))
    }

    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

    /** Popup rows of the gutter on a Go declaration: `·add` in `add_amd64.s`. */
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = "TEXT $text"
        override fun getLocationString(): String? = containingFile?.name
        override fun getIcon(unused: Boolean): Icon = GoAsmFileType.icon
    }

    override fun toString(): String = "GoAsmSymbol($text)"
}

/** `·add` → the Go function `add` of the directory; soft, since a symbol may live in another package (`runtime·x` outside runtime). */
class GoAsmSymbolReference(element: GoAsmSymbol, range: TextRange) : PsiReferenceBase<GoAsmSymbol>(element, range, true) {
    override fun resolve(): PsiElement? = GoAsmNavigation.goFunctions(element).firstOrNull()
}

/** Both directions of `TEXT ·Name(SB)` ↔ `func Name(...)` without a body: within one directory, the way `go build` pairs them. */
object GoAsmNavigation {
    private val TEXT_SYMBOLS = Key.create<CachedValue<Map<String, List<GoAsmSymbol>>>>("gopsi.asm.textSymbols")

    /**
     * The Go functions a symbol names: the stub index of functions limited to the directory of the `.s` file (no AST of the Go files is
     * loaded), the files of the symbol's package only. Several candidates (one per build constraint, e.g. `add_generic.go` with a body)
     * are ordered with the bodyless first; only then are their ASTs loaded.
     */
    fun goFunctions(symbol: GoAsmSymbol): List<GoFunctionDeclaration> {
        val name = symbol.asmName ?: return emptyList()
        val dir = directory(symbol.containingFile?.originalFile?.virtualFile) ?: return emptyList()
        val project = symbol.project
        val scope = GlobalSearchScopesCore.directoryScope(project, dir, false)
        val found = StubIndex.getElements(GoFunctionIndex.KEY, name.name, project, scope, GoFunctionDeclaration::class.java)
            .filter { f -> (f.containingFile as? GoFile)?.packageName.let { !(it ?: "").endsWith("_test") && name.inPackage(it) } }
        return if (found.size > 1) found.sortedBy { it.block != null } else found
    }

    /** The `TEXT` symbols of the `.s` files next to [function] that define it; empty for a function with a body. */
    fun asmSymbols(function: GoFunctionDeclaration): List<GoAsmSymbol> {
        val name = function.name ?: return emptyList()
        val file = function.containingFile as? GoFile ?: return emptyList()
        val dir = directory(file.originalFile.virtualFile) ?: return emptyList()
        val psiManager = PsiManager.getInstance(function.project)
        return dir.children.asSequence()
            .filter { !it.isDirectory && it.nameSequence.endsWith(".s") }
            .sortedBy { it.name }
            .mapNotNull { psiManager.findFile(it) as? GoAsmFile }
            .flatMap { textSymbols(it)[name].orEmpty() }
            .filter { it.asmName?.inPackage(file.packageName) == true }
            .toList()
    }

    /** Name → TEXT symbols of an assembly file, cached on the file (a runtime directory asks this once per bodyless declaration). */
    private fun textSymbols(file: GoAsmFile): Map<String, List<GoAsmSymbol>> = CachedValuesManager.getCachedValue(file, TEXT_SYMBOLS) {
        val map = file.textSymbols.mapNotNull { s -> s.asmName?.let { it.name to s } }.groupBy({ it.first }, { it.second })
        CachedValueProvider.Result.create(map, file)
    }

    private fun directory(file: VirtualFile?): VirtualFile? = file?.parent
}
