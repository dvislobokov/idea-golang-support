package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.template.impl.TemplateManagerImpl

/** G4 imports: Import for side-effects, Add import alias, Add / Remove dot import alias. */
class GoImportIntentionsTest : GoIntentionTestSupport() {

    fun testImportForSideEffects() = doTest(
        """
        package p

        import (
        	"fmt"
        	"str<caret>ings"
        )

        func f() { fmt.Println() }
        """,
        "Import for side-effects",
        """
        package p

        import (
        	"fmt"
        	_ "strings"
        )

        func f() { fmt.Println() }
        """,
    )

    fun testImportForSideEffectsReplacesAnAlias() = doTest(
        """
        package p

        import s "str<caret>ings"
        """,
        "Import for side-effects",
        """
        package p

        import _ "strings"
        """,
    )

    fun testNoSideEffectsImportForAUsedPackage() = assertNotOffered(
        """
        package p

        import "str<caret>ings"

        var s = strings.ToUpper("a")
        """,
        "Import for side-effects",
    )

    fun testNoSideEffectsImportForABlankImport() = assertNotOffered(
        """
        package p

        import _ "str<caret>ings"
        """,
        "Import for side-effects",
    )

    fun testAddImportAliasRenamesTheUses() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        myFixture.configureByText(
            "a.go",
            """
            package p

            import "str<caret>ings"

            var s = strings.ToUpper("a")

            func f(b *strings.Builder) {}
            """.trimIndent() + "\n",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Add import alias"))
        myFixture.checkResult(
            """
            package p

            import strings "strings"

            var s = strings.ToUpper("a")

            func f(b *strings.Builder) {}
            """.trimIndent() + "\n",
        )
        myFixture.type("str")
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        myFixture.checkResult(
            """
            package p

            import str "strings"

            var s = str.ToUpper("a")

            func f(b *str.Builder) {}
            """.trimIndent() + "\n",
        )
    }

    fun testNoAliasForAnAliasedImport() = assertNotOffered(
        """
        package p

        import s "str<caret>ings"

        var x = s.ToUpper("a")
        """,
        "Add import alias",
    )

    fun testAddDotImportAlias() = doTest(
        """
        package p

        import "str<caret>ings"

        var s = strings.ToUpper("a")

        func f(b *strings.Builder) {}
        """,
        "Add dot import alias",
        """
        package p

        import . "strings"

        var s = ToUpper("a")

        func f(b *Builder) {}
        """,
    )

    fun testNoDotImportWhenANameIsTaken() = assertNotOffered(
        """
        package p

        import "str<caret>ings"

        func ToUpper(s string) string { return s }

        var s = strings.ToUpper("a")
        """,
        "Add dot import alias",
    )

    fun testRemoveDotImportAlias() = doTest(
        """
        package p

        import <caret>. "strings"

        var s = ToUpper("a")

        func f(b *Builder) string {
        	x := "b"
        	return x
        }
        """,
        "Remove dot import alias",
        """
        package p

        import "strings"

        var s = strings.ToUpper("a")

        func f(b *strings.Builder) string {
        	x := "b"
        	return x
        }
        """,
    )

    fun testNoRemoveDotOnAPlainImport() = assertNotOffered(
        """
        package p

        import "str<caret>ings"

        var s = strings.ToUpper("a")
        """,
        "Remove dot import alias",
    )
}
