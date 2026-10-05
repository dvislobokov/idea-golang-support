package io.github.golangsupport.ide.rename

import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.GoRenameChoice
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFieldDefinition
import com.intellij.psi.util.PsiTreeUtil

/** The linked renames of PLAN.md G8: a file and its test file, a field and its struct tags, with Ask / Always / Never. */
class GoLinkedRenamesTest : GoSemanticIdeTestBase() {
    private val options get() = GoIdeOptions.getInstance()

    override fun tearDown() {
        try {
            options.renameTestFiles = GoRenameChoice.ASK
            options.renameStructTags = GoRenameChoice.ASK
        } finally {
            super.tearDown()
        }
    }

    /** What the rename dialog does: the factories with a check box that is on join the processor; those without one join by themselves. */
    private fun renameAsTheDialog(element: PsiElement, newName: String) {
        val processor = RenameProcessor(project, element, newName, false, false)
        AutomaticRenamerFactory.EP_NAME.extensionList.filter { it.isApplicable(element) && it.optionName != null && it.isEnabled }.forEach(processor::addRenamerFactory)
        processor.run()
    }

    private fun files(): Set<String> = myFixture.file.containingDirectory.files.map { it.name }.toSet()

    fun testTestFileFollowsTheFileWhenAsked() {
        myFixture.addFileToProject("store_test.go", "package store\n")
        myFixture.configureByText("store.go", "package store\n")
        renameAsTheDialog(myFixture.file, "shop.go")
        assertTrue(files().toString(), files().containsAll(listOf("shop.go", "shop_test.go")))
    }

    fun testProductionFileFollowsTheTestFileAlways() {
        options.renameTestFiles = GoRenameChoice.ALWAYS
        myFixture.addFileToProject("cart.go", "package store\n")
        myFixture.configureByText("cart_test.go", "package store\n")
        myFixture.renameElement(myFixture.file, "basket_test.go")
        assertTrue(files().toString(), files().containsAll(listOf("basket.go", "basket_test.go")))
    }

    fun testNeverAndAnUncheckedBoxLeaveTheTestFile() {
        options.renameTestFiles = GoRenameChoice.NEVER
        myFixture.addFileToProject("order_test.go", "package store\n")
        myFixture.configureByText("order.go", "package store\n")
        renameAsTheDialog(myFixture.file, "deal.go")
        assertTrue(files().toString(), "order_test.go" in files())
        options.renameTestFiles = GoRenameChoice.ASK
        val factory = AutomaticRenamerFactory.EP_NAME.findExtensionOrFail(GoTestFileRenamerFactory::class.java)
        factory.isEnabled = false
        try {
            renameAsTheDialog(myFixture.file, "trade.go")
            assertTrue(files().toString(), "order_test.go" in files())
        } finally {
            factory.isEnabled = true
        }
    }

    fun testCounterpartNames() {
        assertEquals("a_test.go", GoTestFileRenamerFactory.counterpartName("a.go"))
        assertEquals("a.go", GoTestFileRenamerFactory.counterpartName("a_test.go"))
        assertNull(GoTestFileRenamerFactory.counterpartName("_test.go"))
        assertEquals("b_test.go", GoTestFileRenamerFactory.counterpartName("a.go", "b.go"))
        assertNull("a test file renamed to a plain one loses its kind", GoTestFileRenamerFactory.counterpartName("a_test.go", "b.go"))
    }

    private fun field(name: String): GoFieldDefinition =
        PsiTreeUtil.findChildrenOfType(myFixture.file, GoFieldDefinition::class.java).first { it.name == name }

    fun testStructTagsFollowTheFieldInTheirStyle() {
        myFixture.configureByText("u.go", "package u\n\ntype User struct {\n\tUserName string `json:\"user_name,omitempty\" yaml:\"userName\" validate:\"required\"`\n\tLogin    string `json:\"nick\"`\n}\n")
        renameAsTheDialog(field("UserName"), "AccountName")
        renameAsTheDialog(field("Login"), "Handle") // the rename realigns the columns, as gofmt
        assertEquals("package u\n\ntype User struct {\n\tAccountName string `json:\"account_name,omitempty\" yaml:\"accountName\" validate:\"required\"`\n\tHandle      string `json:\"nick\"`\n}\n", myFixture.editor.document.text)
    }

    fun testStructTagsNotRenamedWhenToldNever() {
        options.renameStructTags = GoRenameChoice.NEVER
        myFixture.configureByText("u.go", "package u\n\ntype User struct {\n\tUserName string `json:\"user_name\"`\n}\n")
        renameAsTheDialog(field("UserName"), "AccountName")
        myFixture.checkResult("package u\n\ntype User struct {\n\tAccountName string `json:\"user_name\"`\n}\n")
    }

    fun testStructTagsAlwaysWithoutADialog() {
        options.renameStructTags = GoRenameChoice.ALWAYS
        myFixture.configureByText("u.go", "package u\n\ntype User struct {\n\tID int \"json:\\\"id\\\"\"\n}\n")
        myFixture.renameElement(field("ID"), "UserID")
        // json's style is camelCase when no other field shows another one
        myFixture.checkResult("package u\n\ntype User struct {\n\tUserID int \"json:\\\"userId\\\"\"\n}\n")
    }
}
