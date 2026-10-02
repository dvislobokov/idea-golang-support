package io.github.golangsupport

import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.ide.usages.GoFindUsagesHandlerFactory
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lsp.GoplsFindUsagesHandlerFactory
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/** Exactly one Find Usages handler factory takes a Go declaration: the gopls one (no reference search) or the PSI one, by the Usages switch. */
class GoplsFindUsagesHandlerTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var server = true
    private var usages = GoFeatureSource.GOPLS

    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        usages = settings.usagesSource
    }

    override fun tearDown() {
        try {
            settings.languageServerEnabled = server
            settings.usagesSource = usages
        } finally {
            super.tearDown()
        }
    }

    fun testTheFactoriesAreExclusive() {
        val file = myFixture.configureByText("a.go", "package main\n\nfunc helper() {}\n\nfunc main() { helper() }\n")
        val function = PsiTreeUtil.findChildOfType(file, GoFunctionDeclaration::class.java)!!
        val factories = FindUsagesHandlerFactory.EP_NAME.getExtensions(project)
        val gopls = factories.filterIsInstance<GoplsFindUsagesHandlerFactory>().single()
        val psi = factories.filterIsInstance<GoFindUsagesHandlerFactory>().single()
        settings.languageServerEnabled = true
        settings.usagesSource = GoFeatureSource.GOPLS
        assertTrue(gopls.canFindUsages(function))
        assertFalse(psi.canFindUsages(function))
        // the gopls handler searches no references: what gopls answers is the whole answer
        val handler = gopls.createFindUsagesHandler(function, false)
        assertTrue(handler.processElementUsages(function, { fail("a reference was searched"); false }, handler.findUsagesOptions))
        settings.usagesSource = GoFeatureSource.NATIVE
        assertFalse(gopls.canFindUsages(function))
        settings.languageServerEnabled = false
        assertFalse(gopls.canFindUsages(function))
    }
}
