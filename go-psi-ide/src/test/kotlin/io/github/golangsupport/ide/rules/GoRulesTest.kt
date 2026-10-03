package io.github.golangsupport.ide.rules

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.inspections.lint.GoUncheckedErrorInspection

/** The rule engine end to end: the sample rules, levels, options, the configuration layers, suppression comments and skipped files. */
class GoRulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    private val settings: GoRuleSettings get() = GoRuleSettings.getInstance(project)

    private fun highlight(name: String, text: String) {
        myFixture.configureByText(name, text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    /** `line: description` of the rule problems in the open file. */
    private fun problems(): List<String> {
        val document = myFixture.editor.document
        return myFixture.doHighlighting().filter { it.description?.startsWith("[") == true }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description}" }.sorted()
    }

    private fun problemsIn(name: String, text: String): List<String> {
        myFixture.configureByText(name, text.trimIndent() + "\n")
        return problems()
    }

    private fun useConfig(config: GoRuleConfig?) {
        val source = object : GoRuleConfigSource {
            override fun configFor(project: Project, file: VirtualFile): GoRuleConfig? = config
        }
        ExtensionTestUtil.maskExtensions(GoRuleConfigSource.EP_NAME, listOf(source), testRootDisposable)
        GoRuleSet.getInstance(project).invalidate()
    }

    private val errcheckFile = """
        package rr

        func rrFail() error { return nil }

        func rrUse() {
        	rrFail()
        }
    """

    // ---- errcheck

    fun testErrcheckReportedOnceWithTheOldInspectionOn() {
        myFixture.enableInspections(GoUncheckedErrorInspection())
        highlight("rr1.go", """
            package rr

            func rrFail() error { return nil }

            func rrUse() {
            	<warning descr="[errcheck] Error return value of `rrFail` is not checked">rrFail()</warning>
            	_ = rrFail()
            	defer rrFail()
            }
        """)
    }

    fun testDisabledRuleLeavesTheOldInspection() {
        myFixture.enableInspections(GoUncheckedErrorInspection())
        settings.setEnabled("errcheck", false)
        highlight("rr2.go", """
            package rr

            func rrFail() error { return nil }

            func rrUse() {
            	<warning descr="Error return value of `rrFail` is not checked">rrFail()</warning>
            }
        """)
    }

    fun testUserLevel() {
        settings.setLevel("errcheck", GoRuleLevel.ERROR)
        highlight("rr3.go", """
            package rr

            func rrFail() error { return nil }

            func rrUse() {
            	<error descr="[errcheck] Error return value of `rrFail` is not checked">rrFail()</error>
            }
        """)
    }

    // ---- S1002

    fun testBoolComparison() {
        highlight("rr4.go", """
            package rr

            func rrBool(b bool, n int) {
            	if <weak_warning descr="[S1002] should omit comparison to bool constant, can be simplified to b">b == true</weak_warning> {
            	}
            	if <weak_warning descr="[S1002] should omit comparison to bool constant, can be simplified to !b">false == b</weak_warning> {
            	}
            	if <weak_warning descr="[S1002] should omit comparison to bool constant, can be simplified to !(n > 1)">n > 1 != true</weak_warning> {
            	}
            	if b {
            	}
            	x := <weak_warning descr="[S1002] should omit comparison to bool constant, can be simplified to b">b == true</weak_warning>
            	_ = x
            }

            func rrShadow(b bool) {
            	true := false
            	if b == true {
            	}
            }
        """)
    }

    fun testBoolComparisonFix() {
        myFixture.configureByText("rr5.go", "package rr\n\nfunc rrFix(b bool) {\n\tif b <caret>== false {\n\t}\n}\n")
        myFixture.launchAction(myFixture.findSingleIntention("Replace with '!b'"))
        myFixture.checkResult("package rr\n\nfunc rrFix(b bool) {\n\tif !b {\n\t}\n}\n")
    }

    // ---- function-result-limit: opt-in, option max

    private val fourResults = """
        package rr

        func rrFour() (int, int, string, error) { return 0, 0, "", nil }

        func rrNamed() (a, b int, c string) { return }

        var rrLit = func() (int, int, int, int, int) { return 0, 0, 0, 0, 0 }
    """

    fun testFunctionResultLimitOffByDefault() {
        assertEmpty(problemsIn("rr6.go", fourResults))
    }

    fun testFunctionResultLimitEnabledAndOption() {
        settings.setEnabled("revive:function-result-limit", true)
        assertEquals(
            listOf(
                "3: [revive:function-result-limit] maximum number of return results per function exceeded; max 3 but got 4",
                "7: [revive:function-result-limit] maximum number of return results per function exceeded; max 3 but got 5",
            ),
            problemsIn("rr7.go", fourResults),
        )
        settings.setOption("revive:function-result-limit", "max", "4")
        assertEquals(listOf("7: [revive:function-result-limit] maximum number of return results per function exceeded; max 4 but got 5"), problems())
    }

    // ---- interfacebloat: options through the configuration source, the user's settings above it

    private fun bloated(name: String) = problemsIn(name, """
        package rr

        type rrBig interface {
        ${(1..11).joinToString("\n") { "\tM$it()" }}
        }

        type rrSmall interface {
        	M()
        }
    """)

    fun testInterfaceBloat() {
        assertEquals(listOf("3: [interfacebloat] the interface has more than 10 methods: 11"), bloated("rr8.go"))
    }

    fun testConfigOptionsAndUserOverride() {
        useConfig(GoRuleConfig(linterOptions = mapOf("interfacebloat" to mapOf("max" to 11))))
        assertEmpty(bloated("rr9.go"))
        settings.setOption("interfacebloat", "max", "5")
        assertEquals(listOf("3: [interfacebloat] the interface has more than 5 methods: 11"), problems())
    }

    fun testConfigDisablesLinterAndUserEnablesRule() {
        useConfig(GoRuleConfig(linters = mapOf("interfacebloat" to false)))
        assertEmpty(bloated("rr10.go"))
        settings.setEnabled("interfacebloat", true)
        assertEquals(1, problems().size)
    }

    fun testConfigDefaultNone() {
        useConfig(GoRuleConfig(defaultEnabled = false, linters = mapOf("errcheck" to true)))
        assertEquals(listOf("6: [errcheck] Error return value of `rrFail` is not checked"), problemsIn("rr11.go", errcheckFile))
        assertEmpty(bloated("rr12.go"))
    }

    // ---- package-comments: one report per package, in the first file

    fun testPackageComments() {
        settings.setEnabled("revive:package-comments", true)
        val a = myFixture.addFileToProject("rrpc/a.go", "package rrpc\n\nfunc A() {}\n").virtualFile
        val b = myFixture.addFileToProject("rrpc/b.go", "//go:build linux\n\npackage rrpc\n").virtualFile
        myFixture.configureFromExistingVirtualFile(a)
        assertEquals(listOf("1: [revive:package-comments] should have a package comment"), problems())
        myFixture.configureFromExistingVirtualFile(b)
        assertEmpty(problems())
        myFixture.addFileToProject("rrpc/doc.go", "// Package rrpc does things.\npackage rrpc\n")
        myFixture.configureFromExistingVirtualFile(a)
        assertEmpty(problems())
    }

    // ---- suppression

    fun testNolintAtLineEnd() {
        assertEquals(
            listOf("10: [errcheck] Error return value of `rrFail` is not checked", "9: [errcheck] Error return value of `rrFail` is not checked"),
            problemsIn("rr13.go", """
                package rr

                func rrFail() error { return nil }

                func rrUse() {
                	rrFail() //nolint
                	rrFail() //nolint:errcheck // reason
                	rrFail() //nolint:govet,errcheck
                	rrFail() //nolint:govet
                	rrFail() // nolint:errcheck
                }
            """),
        )
    }

    fun testNolintOnDeclarationLineAndAbove() {
        assertEquals(
            listOf("16: [errcheck] Error return value of `rrFail` is not checked"),
            problemsIn("rr14.go", """
                package rr

                func rrFail() error { return nil }

                func rrWhole() { //nolint:errcheck
                	rrFail()
                	rrFail()
                }

                //nolint:all
                func rrDocumented() {
                	rrFail()
                }

                func rrAbove() {
                	rrFail()
                	//nolint:errcheck
                	if true {
                		rrFail()
                	}
                }
            """),
        )
    }

    fun testLintIgnoreAndLinterAlias() {
        assertEmpty(problemsIn("rr15.go", """
            package rr

            func rrIgnore(b bool) {
            	//lint:ignore S1002 kept for readability
            	if b == true {
            	}
            	if b == false { //nolint:gosimple
            	}
            	if b != false { //lint:ignore SA*,S1* reason
            	}
            }
        """))
        assertEmpty(problemsIn("rr16.go", """
            //lint:file-ignore S1002 generated-ish

            package rr

            func rrIgnore2(b bool) {
            	if b == true {
            	}
            }
        """))
    }

    fun testNoinspectionAliasIdAndTool() {
        assertEquals(
            listOf("13: [errcheck] Error return value of `rrFail` is not checked"),
            problemsIn("rr17.go", """
                package rr

                func rrFail() error { return nil }

                func rrUse() {
                	//noinspection GoUncheckedError
                	rrFail()
                	//noinspection errcheck
                	rrFail()
                	//noinspection GoRules
                	rrFail()
                	//noinspection GoPrintf
                	rrFail()
                }
            """),
        )
    }

    // ---- what is not analysed, gopls mode

    fun testVendorTestdataAndGeneratedSkipped() {
        for (path in listOf("vendor/rrv/v.go", "rrt/testdata/t.go")) {
            myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, errcheckFile.trimIndent()).virtualFile)
            assertEmpty(path, problems())
        }
        assertEmpty(problemsIn("rr18.go", "// Code generated by rrgen. DO NOT EDIT.\n\n" + errcheckFile.trimIndent()))
        assertEquals(1, problemsIn("rr19.go", errcheckFile).size)
    }

    fun testGoplsModeKeepsRulesThatDoNotOverlap() {
        val gate = object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project) = false
        }
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, gate, testRootDisposable)
        assertEquals(1, problemsIn("rr20.go", errcheckFile).size)
    }
}
