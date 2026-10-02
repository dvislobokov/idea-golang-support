package io.github.golangsupport.lang.stubs

import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.AstLoadingFilter
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.stubs.index.GoFunctionIndex
import io.github.golangsupport.lang.stubs.index.GoTypesIndex

/**
 * Package-level API and indices must work from stubs alone: any AST load of the file fails the test
 * (`PsiManagerEx.setAssertOnFileLoadingFilter` + `AstLoadingFilter.disallowTreeLoading`).
 */
class GoAstLoadingTest : GoCodeInsightTestBase() {

    private val source = """
        //go:build !windows

        package lib

        import (
        	"io"
        	. "strings"
        )

        type Shape interface {
        	Area() float64
        	Scale(f float64, g ...int) Shape
        }

        type Box[T any] struct {
        	*Inner
        	W, H float64 `json:"w"`
        	items []T
        }

        type Inner struct{}

        type Alias = Box[int]

        func New[T any](w, h float64) *Box[T] { return &Box[T]{W: w, H: h} }

        func (b *Box[T]) Area() float64 { return b.W * b.H }

        func (b Box[T]) Scale(f float64, g ...int) Shape { return nil }

        var Default, other = New[int](1, 2), 3

        const (
        	A = iota
        	B
        )

        func use(r io.Reader) string { return ToUpper("x") }
    """.trimIndent()

    fun testPackageLevelApiDoesNotLoadAst() {
        val file = myFixture.addFileToProject("lib/lib.go", source) as GoFile
        assertNotNull("precondition: the file must have a stub", (file as PsiFileImpl).stub)
        assertFalse(file.isContentsLoaded)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter.ALL, testRootDisposable)

        val summary = AstLoadingFilter.disallowTreeLoading<String, RuntimeException> {
            buildString {
                append(file.packageName).append(' ').append(file.isTestFile).append(' ').append(file.buildConstraint.goBuild).append('\n')
                append(file.imports.joinToString { "${it.name}=${it.path} dot=${it.isDot}" }).append('\n')
                append(file.functions.joinToString { fn ->
                    val params = fn.signature!!.parameters.parameterDeclarationList.flatMap { it.paramDefinitionList }.map { it.name }
                    "${fn.name}${params} public=${fn.isPublic()} tp=${fn.typeParameters != null}"
                }).append('\n')
                append(file.methods.joinToString { "${it.name}(${if (it.isPointerReceiver) "*" else ""}${it.receiverTypeName})" }).append('\n')
                append(file.types.joinToString { t ->
                    val kind = when (t.type) {
                        is GoStructType -> "struct"
                        is GoInterfaceType -> "interface"
                        else -> t.type?.javaClass?.simpleName
                    }
                    "${t.name}:$kind alias=${t.isAlias}"
                }).append('\n')
                val box = file.types.first { it.name == "Box" }
                val fields = (box.type as GoStructType).fieldDeclarationList.flatMap { fd ->
                    fd.fieldDefinitionList.map { it.name } + listOfNotNull(fd.anonymousFieldDefinition?.name)
                }
                append(fields).append('\n')
                val shape = file.types.first { it.name == "Shape" }.type as GoInterfaceType
                append(shape.methodSpecList.joinToString { "${it.name}/${it.signature.parameters.parameterDeclarationList.size}" }).append('\n')
                append(file.vars.map { it.name }).append(' ').append(file.consts.map { it.name }).append('\n')
                val newFn = GoFunctionIndex().find("New", project, GlobalSearchScope.allScope(project)).single()
                append(newFn.signature?.result?.type?.javaClass?.simpleName).append('\n')
                append(GoTypesIndex().find("Alias", project, GlobalSearchScope.allScope(project)).single().isAlias)
            }
        }
        assertFalse("AST must not be loaded", file.isContentsLoaded)
        assertEquals(
            """
            lib false !windows
            io=io dot=false, .=strings dot=true
            New[w, h] public=true tp=true, use[r] public=false tp=false
            Area(*Box), Scale(Box)
            Shape:interface alias=false, Box:struct alias=false, Inner:struct alias=false, Alias:GoTypeImpl alias=true
            [Inner, W, H, items]
            Area/0, Scale/2
            [Default, other] [A, B]
            GoPointerTypeImpl
            true
            """.trimIndent(),
            summary,
        )
    }
}
