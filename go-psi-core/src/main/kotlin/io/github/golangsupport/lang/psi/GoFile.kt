package io.github.golangsupport.lang.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.util.concurrent.ConcurrentHashMap
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner
import io.github.golangsupport.lang.stubs.GoFileStub
import io.github.golangsupport.lang.stubs.GoStubPolicy

/**
 * A Go source file. Every accessor below is stub-first: while the AST is not loaded the
 * answer comes from the stub tree (`getStub()`, never loads the AST); otherwise the PSI is traversed the
 * same way the stub builder does (package-level declarations only, no function bodies).
 */
class GoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GoLanguage) {
    override fun getFileType(): FileType = GoFileType

    override fun toString(): String = "Go file"

    private val fileStub: GoFileStub? get() = stub as? GoFileStub

    val packageClause: GoPackageClause?
        get() = topLevel<GoPackageClause>(GoTypes.PACKAGE_CLAUSE).firstOrNull()

    /** The doc comment of the package clause (first comment of the run, see [GoNamedElement.docComment]), or `null`. */
    val packageDoc: PsiComment? get() = packageClause?.docComment

    /** [packageDoc] rendered like `go/doc` does, see [GoNamedElement.docText]. */
    val packageDocText: String? get() = packageClause?.docText

    /** The package name from the package clause, `null` if missing. */
    val packageName: String?
        get() {
            val stub = fileStub
            return if (stub != null) stub.packageName else packageClause?.name
        }

    /** True for `*_test.go` files. */
    val isTestFile: Boolean
        get() = fileStub?.isTestFile ?: GoFileStub.isTestFileName(name)

    /** True if the file imports `"C"`. */
    val isCgo: Boolean
        get() = fileStub?.isCgo ?: imports.any { it.path == "C" }

    /** The raw `//go:build` / `// +build` constraint of the file header. */
    val buildConstraint: GoBuildConstraint
        get() = fileStub?.buildConstraint
            ?: GoFileHeaderScanner.scan(viewProvider.contents, withImports = false).buildConstraint

    val imports: List<GoImportSpec> get() = topLevel(GoTypes.IMPORT_SPEC)

    val functions: List<GoFunctionDeclaration> get() = topLevel(GoTypes.FUNCTION_DECLARATION)

    val methods: List<GoMethodDeclaration> get() = topLevel(GoTypes.METHOD_DECLARATION)

    val types: List<GoTypeSpec> get() = topLevel(GoTypes.TYPE_SPEC)

    val vars: List<GoVarDefinition>
        get() = topLevel<GoVarDeclaration>(GoTypes.VAR_DECLARATION).flatMap { declaration ->
            children<GoVarSpec>(declaration, GoTypes.VAR_SPEC).flatMap { children<GoVarDefinition>(it, GoTypes.VAR_DEFINITION) }
        }

    val consts: List<GoConstDefinition>
        get() = topLevel<GoConstDeclaration>(GoTypes.CONST_DECLARATION).flatMap { declaration ->
            children<GoConstSpec>(declaration, GoTypes.CONST_SPEC).flatMap { children<GoConstDefinition>(it, GoTypes.CONST_DEFINITION) }
        }

    /** Package-level declarations of [type]: stub children of the file, or the matching AST nodes. */
    internal fun <T : PsiElement> topLevel(type: IElementType): List<T> {
        val stub = fileStub
        if (stub != null) return stubChildren(stub, type)
        // With the AST loaded every accessor walked all top-level nodes; resolve asks for `imports`
        // and `packageClause` per name, which on files of thousands of declarations
        // (`ssa/rewriteAMD64.go`) was quadratic. Cached per file modification stamp.
        val byType = CachedValuesManager.getCachedValue(this, TOP_LEVEL_KEY) {
            CachedValueProvider.Result.create(ConcurrentHashMap<IElementType, List<PsiElement>>(), this)
        }
        @Suppress("UNCHECKED_CAST")
        return byType.computeIfAbsent(type) { topLevelFromAst<PsiElement>(it) } as List<T>
    }

    private companion object {
        val TOP_LEVEL_KEY: Key<CachedValue<ConcurrentHashMap<IElementType, List<PsiElement>>>> = Key.create("gopsi.topLevelFromAst")
    }

    /** The AST path of [topLevel]; also used by tests to compare both paths. */
    internal fun <T : PsiElement> topLevelFromAst(type: IElementType): List<T> {
        val result = mutableListOf<T>()
        fun visit(parent: ASTNode) {
            var child = parent.firstChildNode
            while (child != null) {
                val childType = child.elementType
                if (childType === type) {
                    @Suppress("UNCHECKED_CAST")
                    result += child.psi as T
                } else if (GoStubPolicy.TRANSPARENT_CONTAINERS.contains(childType)) {
                    visit(child)
                }
                child = child.treeNext
            }
        }
        visit(node)
        return result
    }

    private fun <T : PsiElement> children(parent: PsiElement, type: IElementType): List<T> {
        val stub = (parent as? com.intellij.psi.StubBasedPsiElement<*>)?.let {
            (it as? com.intellij.extapi.psi.StubBasedPsiElementBase<*>)?.greenStub
        }
        if (stub != null) return stubChildren(stub, type)
        @Suppress("UNCHECKED_CAST")
        return parent.node.getChildren(null).filter { it.elementType === type }.map { it.psi as T }
    }

    private fun <T : PsiElement> stubChildren(stub: StubElement<*>, type: IElementType): List<T> {
        @Suppress("UNCHECKED_CAST")
        return stub.childrenStubs.filter { it.elementType === type }.map { it.psi as T }
    }
}
