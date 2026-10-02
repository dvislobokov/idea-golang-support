package io.github.golangsupport.lang.stubs

import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.tree.IStubFileElementType
import io.github.golangsupport.lang.psi.GoBuildConstraint
import io.github.golangsupport.lang.psi.GoFile

/**
 * File stub: package name, raw build constraint (`//go:build` expression and `// +build` lines),
 * whether the file is a `_test.go` file and whether it imports `"C"` (cgo).
 */
class GoFileStub(
    file: GoFile?,
    val packageName: String?,
    val buildConstraint: GoBuildConstraint,
    val isTestFile: Boolean,
    val isCgo: Boolean,
) : PsiFileStubImpl<GoFile>(file) {

    override fun getType(): IStubFileElementType<*> = GoFileElementType.INSTANCE

    override fun toString(): String = buildString {
        append("GoFileStub(package=").append(packageName)
        if (buildConstraint.goBuild != null) append(", goBuild=").append(buildConstraint.goBuild)
        if (buildConstraint.plusBuild.isNotEmpty()) append(", plusBuild=").append(buildConstraint.plusBuild)
        if (isTestFile) append(", test")
        if (isCgo) append(", cgo")
        append(')')
    }

    companion object {
        const val TEST_SUFFIX: String = "_test.go"

        @JvmStatic
        fun isTestFileName(name: String): Boolean = name.endsWith(TEST_SUFFIX)
    }
}
