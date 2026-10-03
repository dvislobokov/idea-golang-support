package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/** With the whole plugin (catalogue completion, gates): builtins stay in the list next to the catalogue's symbols. Seen live: `tables := ma` offered bytes.Map only. */
class GoBuiltinCompletionTest : BasePlatformTestCase() {
    private var languageServer = true
    private var source = GoFeatureSource.GOPLS

    override fun setUp() {
        super.setUp()
        val settings = GoSettings.getInstance()
        languageServer = settings.languageServerEnabled
        source = settings.languageFeaturesSource
        settings.languageServerEnabled = false
        settings.languageFeaturesSource = GoFeatureSource.NATIVE
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
            GoSettings.getInstance().languageFeaturesSource = source
        } finally {
            super.tearDown()
        }
    }

    fun testMakeOnTheRightOfShortVarDecl() {
        myFixture.configureByText("a.go", "package main\n\nfunc f() error {\n\ttables := ma<caret>\n\treturn nil\n}\n")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertTrue(items.toString(), "make" in items)
    }

    fun testTypePlaces() {
        fun place(text: String) = io.github.golangsupport.lang.GoStructLiterals.isTypePlace(text, text.length)
        assertTrue(place("type U struct {\n\tName "))
        assertTrue(place("type U struct {\n\tTags []"))
        assertTrue(place("func f() {\n\tvar x "))
        assertTrue(place("func f(name "))
        assertTrue(place("func f(a int, b *"))
        assertFalse(place("func f() {\n\tName "))
        assertFalse(place("func f() {\n\tx := "))
        assertFalse(place("func f() {\n\tfmt.Println("))
    }

    // seen live: a field type offered fmt.Stringer, flag.String (a function), reflect.String (a constant) and no `string`
    fun testFieldTypeOffersTypesFirst() {
        myFixture.configureByText("b.go", "package main\n\ntype User struct {\n\tName str<caret>\n}\n")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertTrue(items.toString(), items.indexOf("string") in 0..1)
    }
}
