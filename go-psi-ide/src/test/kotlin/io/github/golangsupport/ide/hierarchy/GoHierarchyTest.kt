package io.github.golangsupport.ide.hierarchy

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.LanguageCallHierarchy
import com.intellij.ide.hierarchy.LanguageTypeHierarchy
import com.intellij.ide.hierarchy.actions.BrowseHierarchyActionBase
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.testFramework.codeInsight.hierarchy.HierarchyViewTestFixture
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec

/** Call hierarchy (callers, callees, calls through interfaces, recursion) and type hierarchy (implementation, embedding), and the gate. */
class GoHierarchyTest : GoSemanticIdeTestBase() {

    private val shapes = """
        package store

        type H8Shape interface {
        	H8Area() float64
        }

        type H8Named interface {
        	H8Shape
        	H8Name() string
        }

        type H8Base struct{ id int }

        type H8Circle struct {
        	H8Base
        	r float64
        }

        func (c *H8Circle) H8Area() float64 { return c.r * c.r }

        type H8Square struct{ side float64 }

        func (s H8Square) H8Area() float64 { return s.side * s.side }

        func (s H8Square) H8Name() string { return "square" }

        func h8Total(shapes []H8Shape) float64 {
        	sum := 0.0
        	for _, s := range shapes {
        		sum += s.H8Area()
        	}
        	return sum
        }

        func h8Report() float64 {
        	c := &H8Circle{r: 1}
        	f := func() float64 { return h8Total([]H8Shape{c}) }
        	return f() + h8Total(nil) + c.H8Area() + float64(len("x"))
        }

        func h8Walk(n int) {
        	if n > 0 {
        		h8Walk(n - 1)
        	}
        }

        var h8Default = h8Report()
    """.trimIndent()

    private fun configure(text: String = shapes) = myFixture.configureByText("shapes.go", text)

    private inline fun <reified T : GoNamedElement> declaration(name: String, receiver: String? = null): T =
        com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, T::class.java)
            .single { it.name == name && (receiver == null || (it as? GoMethodDeclaration)?.receiverTypeName == receiver) }

    private fun check(structure: HierarchyTreeStructure, expected: String) {
        val actual = HierarchyViewTestFixture.dump(structure, null, null, 0)
        try {
            HierarchyViewTestFixture.doHierarchyTest(structure, expected.trimIndent())
        } catch (e: Throwable) {
            throw AssertionError("actual tree:\n$actual", e)
        }
    }

    private fun callers(element: PsiElement) = GoCallerTreeStructure(project, element, HierarchyBrowserBaseEx.SCOPE_ALL)

    private fun callees(element: PsiElement) = GoCalleeTreeStructure(project, element)

    private fun types(spec: GoTypeSpec, supertypes: Boolean) = GoTypeTreeStructure(project, spec, HierarchyBrowserBaseEx.SCOPE_ALL, supertypes)

    // --- call hierarchy ---

    fun testCallersGroupedByEnclosingDeclarationIncludingFunctionLiterals() {
        configure()
        check(callers(declaration<GoFunctionDeclaration>("h8Total")), """
            <node text="h8Total  store (shapes.go)" base="true">
              <node text="h8Report (2 usages)  store (shapes.go)">
                <node text="h8Default  store (shapes.go)"/>
              </node>
            </node>
        """)
    }

    fun testCalleesSkipBuiltinsConversionsAndLocalFunctionValues() {
        configure()
        check(callees(declaration<GoFunctionDeclaration>("h8Report")), """
            <node text="h8Report  store (shapes.go)" base="true">
              <node text="h8Total (2 usages)  store (shapes.go)">
                <node text="H8Shape.H8Area  store (shapes.go)"/>
              </node>
              <node text="H8Circle.H8Area  store (shapes.go)"/>
            </node>
        """)
    }

    fun testCallersOfAnImplementationIncludeCallsThroughTheInterface() {
        configure()
        check(callers(declaration<GoMethodDeclaration>("H8Area", "H8Circle")), """
            <node text="H8Circle.H8Area  store (shapes.go)" base="true">
              <node text="h8Report  store (shapes.go)">
                <node text="h8Default  store (shapes.go)"/>
              </node>
              <node text="h8Total via H8Shape  store (shapes.go)">
                <node text="h8Report (2 usages)  store (shapes.go)">
                  <node text="h8Default  store (shapes.go)"/>
                </node>
              </node>
            </node>
        """)
    }

    fun testRecursiveCallerIsShownButNotExpanded() {
        configure()
        check(callers(declaration<GoFunctionDeclaration>("h8Walk")), """
            <node text="h8Walk  store (shapes.go)" base="true">
              <node text="h8Walk  store (shapes.go)"/>
            </node>
        """)
    }

    fun testCallersAcrossFilesOfThePackage() {
        myFixture.addFileToProject("other.go", "package store\n\nfunc h8Use() { _ = h8Total(nil) }\n")
        configure()
        check(callers(declaration<GoFunctionDeclaration>("h8Total")), """
            <node text="h8Total  store (shapes.go)" base="true">
              <node text="h8Report (2 usages)  store (shapes.go)">
                <node text="h8Default  store (shapes.go)"/>
              </node>
              <node text="h8Use  store (other.go)"/>
            </node>
        """)
    }

    // --- type hierarchy ---

    fun testSubtypesOfAnInterfaceAreImplementationsAndEmbedders() {
        configure()
        check(types(declaration<GoTypeSpec>("H8Shape"), supertypes = false), """
            <node text="H8Shape  store (shapes.go)" base="true">
              <node text="H8Circle  store (shapes.go)"/>
              <node text="H8Named  store (shapes.go)">
                <node text="H8Square  store (shapes.go)"/>
              </node>
              <node text="H8Square  store (shapes.go)"/>
            </node>
        """)
    }

    fun testSupertypesAreImplementedInterfacesAndEmbeddedTypes() {
        configure()
        check(types(declaration<GoTypeSpec>("H8Circle"), supertypes = true), """
            <node text="H8Circle  store (shapes.go)" base="true">
              <node text="H8Base  store (shapes.go)"/>
              <node text="H8Shape  store (shapes.go)"/>
            </node>
        """)
        check(types(declaration<GoTypeSpec>("H8Square"), supertypes = true), """
            <node text="H8Square  store (shapes.go)" base="true">
              <node text="H8Named  store (shapes.go)">
                <node text="H8Shape  store (shapes.go)"/>
              </node>
              <node text="H8Shape  store (shapes.go)"/>
            </node>
        """)
    }

    fun testSubtypesOfAStructAreTheStructsEmbeddingIt() {
        configure()
        check(types(declaration<GoTypeSpec>("H8Base"), supertypes = false), """
            <node text="H8Base  store (shapes.go)" base="true">
              <node text="H8Circle  store (shapes.go)"/>
            </node>
        """)
    }

    // --- providers and the gate ---

    private fun dataContext(): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.PSI_FILE, myFixture.file)
        .build()

    fun testProvidersFindTheTargetAtTheCaret() {
        configure(shapes.replace("return f() + h8Total(nil)", "return f() + h8To<caret>tal(nil)"))
        val call = BrowseHierarchyActionBase.findBestHierarchyProvider(LanguageCallHierarchy.INSTANCE, myFixture.file, dataContext())
        assertInstanceOf(call, GoCallHierarchyProvider::class.java)
        assertEquals("h8Total", (call!!.getTarget(dataContext()) as GoNamedElement).name)
        configure(shapes.replace("func (s H8Square) H8Name()", "func (s H8Square) H8Na<caret>me()"))
        val type = BrowseHierarchyActionBase.findBestHierarchyProvider(LanguageTypeHierarchy.INSTANCE, myFixture.file, dataContext())
        assertInstanceOf(type, GoTypeHierarchyProvider::class.java)
        assertEquals("H8Square", (type!!.getTarget(dataContext()) as GoTypeSpec).name)
    }

    fun testClosedGateGivesNoTarget() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.NAVIGATION
        }, testRootDisposable)
        configure(shapes.replace("return f() + h8Total(nil)", "return f() + h8To<caret>tal(nil)"))
        assertNull(GoCallHierarchyProvider().getTarget(dataContext()))
        assertNull(GoTypeHierarchyProvider().getTarget(dataContext()))
        // The platform falls back to the first provider when none has a target: the action then stays disabled.
        assertNull(BrowseHierarchyActionBase.findBestHierarchyProvider(LanguageCallHierarchy.INSTANCE, myFixture.file, dataContext())?.getTarget(dataContext()))
        // Another provider for Go (the host's language server) that has a target wins.
        val other = object : HierarchyProvider {
            override fun getTarget(dataContext: DataContext): PsiElement = myFixture.file
            override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = throw UnsupportedOperationException()
            override fun browserActivated(hierarchyBrowser: HierarchyBrowser) = Unit
        }
        LanguageCallHierarchy.INSTANCE.addExplicitExtension(GoLanguage, other, testRootDisposable)
        LanguageTypeHierarchy.INSTANCE.addExplicitExtension(GoLanguage, other, testRootDisposable)
        assertSame(other, BrowseHierarchyActionBase.findBestHierarchyProvider(LanguageCallHierarchy.INSTANCE, myFixture.file, dataContext()))
        assertSame(other, BrowseHierarchyActionBase.findBestHierarchyProvider(LanguageTypeHierarchy.INSTANCE, myFixture.file, dataContext()))
    }
}
