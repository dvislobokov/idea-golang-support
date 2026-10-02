package io.github.golangsupport.lang.stubs

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.DefaultStubBuilder
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoTypes
import org.jetbrains.annotations.ApiStatus

/**
 * Builds the Go stub tree. Only stub element types and the [GoStubPolicy.TRANSPARENT_CONTAINERS]
 * are visited: function bodies, expressions and tokens are skipped wholesale (the platform applies
 * the same filter when binding stubs to a loaded AST).
 */
@ApiStatus.Internal
class GoStubBuilder : DefaultStubBuilder() {

    override fun createStubForFile(file: PsiFile): StubElement<*> {
        if (file !is GoFile) return super.createStubForFile(file)
        // Only the constraint is needed (package and imports come from the AST): no lexer pass.
        val constraint = GoFileHeaderScanner.parseBuildConstraint(file.viewProvider.contents)
        return GoFileStub(
            file,
            file.topLevelFromAst<GoPackageClause>(GoTypes.PACKAGE_CLAUSE).firstOrNull()?.name,
            constraint,
            GoFileStub.isTestFileName(file.name),
            file.topLevelFromAst<GoImportSpec>(GoTypes.IMPORT_SPEC).any { it.path == "C" },
        )
    }

    override fun skipChildProcessingWhenBuildingStubs(parent: ASTNode, node: ASTNode): Boolean {
        val type = node.elementType
        return type !is IStubElementType<*, *> && !GoStubPolicy.TRANSPARENT_CONTAINERS.contains(type)
    }
}
