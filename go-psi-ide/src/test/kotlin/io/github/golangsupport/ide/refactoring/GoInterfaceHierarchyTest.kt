package io.github.golangsupport.ide.refactoring

import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * Change Signature over an interface hierarchy and Add Method to Interface, on the four layers of a real service: the interface
 * `IhStore`, a cache wrapper delegating through its `next IhStore` field, the business type and two hand-written mocks; callers in
 * another file.
 */
class GoInterfaceHierarchyTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private val store = """
        package ih

        import "context"

        type IhItem struct{ ID string }

        type IhOptions struct{ Fresh bool }

        // IhStore loads items.
        type IhStore interface {
            Get(ctx context.Context, id string) (IhItem, error)
        }

        type ihCachedStore struct {
            next  IhStore
            cache map[string]IhItem
        }

        func (s *ihCachedStore) Get(ctx context.Context, id string) (IhItem, error) {
            if it, ok := s.cache[id]; ok {
                return it, nil
            }
            return s.next.Get(ctx, id)
        }

        type ihService struct{ prefix string }

        func (svc ihService) Get(ctx context.Context, key string) (IhItem, error) {
            return IhItem{ID: svc.prefix + key}, nil
        }
        """.trimIndent()

    private val mocks = """
        package ih

        import "context"

        type ihStoreMock struct{}

        func (m *ihStoreMock) Get(ctx context.Context, id string) (IhItem, error) {
            panic("not implemented")
        }

        type ihServiceMock struct{ items map[string]IhItem }

        func (m ihServiceMock) Get(_ context.Context, id string) (IhItem, error) {
            return m.items[id], nil
        }
        """.trimIndent()

    private val callers = """
        package ih

        import "context"

        func ihLoad(ctx context.Context, s IhStore) (IhItem, error) {
            return s.Get(ctx, "a")
        }

        func ihDirect(ctx context.Context) {
            svc := ihService{}
            _, _ = svc.Get(ctx, "b")
            m := &ihStoreMock{}
            _, _ = m.Get(ctx, "c")
        }

        var _ IhStore = &ihCachedStore{next: ihService{}}
        """.trimIndent()

    /** The fixture with the caret on the spec's `Get` (or [caretIn] for another declaration), [extra] files added. */
    private fun fixture(caretIn: String = "IhStore", extra: Map<String, String> = emptyMap()): PsiElement {
        val main = when (caretIn) {
            "IhStore" -> store.replace("    Get(ctx", "    <caret>Get(ctx")
            else -> store.replace("($caretIn) Get(", "($caretIn) <caret>Get(")
        }
        myFixture.addFileToProject("ihmocks.go", go(mocks))
        myFixture.addFileToProject("ihuse.go", go(callers))
        for ((name, text) in extra) myFixture.addFileToProject(name, go(text))
        myFixture.configureByText("ihstore.go", go(main))
        val handler = LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)!!.changeSignatureHandler!!
        return handler.findTargetMember(myFixture.file, myFixture.editor)!!
    }

    private fun change(target: PsiElement, edit: (GoChangeSignatureOptions) -> GoChangeSignatureOptions) =
        GoChangeSignatureProcessor(project, target, edit(GoChangeSignature.initial(target))).run()

    private fun conflicts(run: () -> Unit): String {
        try {
            run()
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            return e.messages.sorted().joinToString("\n")
        }
        fail("Expected conflicts")
        return ""
    }

    private fun checkFiles(store: String, mocks: String, callers: String) {
        assertEquals(go(store), myFixture.editor.document.text)
        myFixture.checkResult("ihmocks.go", go(mocks), true)
        myFixture.checkResult("ihuse.go", go(callers), true)
    }

    private fun GoChangeSignatureOptions.order(vararg slots: Int) = copy(parameters = slots.map { parameters.first { p -> p.oldIndex == it } })

    private fun String.sig(old: String, new: String) = replace(old, new)

    fun testAddParameterThroughTheHierarchy() {
        change(fixture()) { it.copy(parameters = it.parameters + GoChangeParameter("opts", "IhOptions", -1, "IhOptions{}")) }
        checkFiles(
            store.sig("Get(ctx context.Context, id string) (IhItem", "Get(ctx context.Context, id string, opts IhOptions) (IhItem")
                .sig("Get(ctx context.Context, key string)", "Get(ctx context.Context, key string, opts IhOptions)")
                .sig("s.next.Get(ctx, id)", "s.next.Get(ctx, id, opts)"),
            mocks.sig("Get(ctx context.Context, id string)", "Get(ctx context.Context, id string, opts IhOptions)")
                .sig("Get(_ context.Context, id string)", "Get(_ context.Context, id string, opts IhOptions)"),
            callers.sig("s.Get(ctx, \"a\")", "s.Get(ctx, \"a\", IhOptions{})").sig("svc.Get(ctx, \"b\")", "svc.Get(ctx, \"b\", IhOptions{})")
                .sig("m.Get(ctx, \"c\")", "m.Get(ctx, \"c\", IhOptions{})"),
        )
    }

    fun testRemoveParameterThroughTheHierarchy() {
        change(fixture()) { it.order(1) }
        checkFiles(
            store.sig("Get(ctx context.Context, id string) (IhItem", "Get(id string) (IhItem").sig("Get(ctx context.Context, key string)", "Get(key string)")
                .sig("s.next.Get(ctx, id)", "s.next.Get(id)"),
            mocks.sig("Get(ctx context.Context, id string)", "Get(id string)").sig("Get(_ context.Context, id string)", "Get(id string)"),
            callers.sig("s.Get(ctx, \"a\")", "s.Get(\"a\")").sig("svc.Get(ctx, \"b\")", "svc.Get(\"b\")").sig("m.Get(ctx, \"c\")", "m.Get(\"c\")"),
        )
    }

    fun testReorderFromAnImplementation() {
        change(fixture(caretIn = "svc ihService")) { it.order(1, 0) }
        checkFiles(
            store.sig("Get(ctx context.Context, id string) (IhItem", "Get(id string, ctx context.Context) (IhItem")
                .sig("Get(ctx context.Context, key string)", "Get(key string, ctx context.Context)").sig("s.next.Get(ctx, id)", "s.next.Get(id, ctx)"),
            mocks.sig("Get(ctx context.Context, id string)", "Get(id string, ctx context.Context)")
                .sig("Get(_ context.Context, id string)", "Get(id string, _ context.Context)"),
            callers.sig("s.Get(ctx, \"a\")", "s.Get(\"a\", ctx)").sig("svc.Get(ctx, \"b\")", "svc.Get(\"b\", ctx)").sig("m.Get(ctx, \"c\")", "m.Get(\"c\", ctx)"),
        )
    }

    fun testRenameMethodAndParameter() {
        change(fixture()) { o -> o.copy(name = "Fetch", parameters = o.parameters.map { if (it.oldIndex == 1) it.copy(name = "itemID") else it }) }
        checkFiles(
            store.replace("Get(", "Fetch(").sig("ctx context.Context, id string", "ctx context.Context, itemID string")
                .sig("s.cache[id]", "s.cache[itemID]").sig("s.next.Fetch(ctx, id)", "s.next.Fetch(ctx, itemID)")
                .sig("ctx context.Context, key string", "ctx context.Context, itemID string").sig("svc.prefix + key", "svc.prefix + itemID"),
            mocks.replace("Get(", "Fetch(").sig("id string", "itemID string").sig("m.items[id]", "m.items[itemID]"),
            callers.replace(".Get(", ".Fetch("),
        )
    }

    fun testSecondInterfaceChangesToo() {
        val getter = "package ih\n\nimport \"context\"\n\ntype IhGetter interface {\n    Get(ctx context.Context, id string) (IhItem, error)\n}\n\nvar _ IhGetter = &ihCachedStore{}"
        change(fixture(extra = mapOf("ihgetter.go" to getter))) { it.copy(parameters = it.parameters + GoChangeParameter("opts", "IhOptions", -1, "IhOptions{}")) }
        myFixture.checkResult("ihgetter.go", go(getter.sig("id string) (IhItem", "id string, opts IhOptions) (IhItem")), true)
        myFixture.checkResult("ihuse.go", go(callers.sig("s.Get(ctx, \"a\")", "s.Get(ctx, \"a\", IhOptions{})")
            .sig("svc.Get(ctx, \"b\")", "svc.Get(ctx, \"b\", IhOptions{})").sig("m.Get(ctx, \"c\")", "m.Get(ctx, \"c\", IhOptions{})")), true)
    }

    fun testEmbeddedInterfaceAndMethodExpression() {
        val embedded = "package ih\n\nimport \"context\"\n\ntype IhStoreCloser interface {\n    IhStore\n    Close() error\n}\n\n" +
            "func ihViaEmbedded(ctx context.Context, sc IhStoreCloser, m *ihStoreMock) {\n    _, _ = sc.Get(ctx, \"e\")\n    _, _ = (*ihStoreMock).Get(m, ctx, \"f\")\n}"
        change(fixture(extra = mapOf("ihembed.go" to embedded))) { it.order(1, 0) }
        myFixture.checkResult("ihembed.go", go(embedded.sig("sc.Get(ctx, \"e\")", "sc.Get(\"e\", ctx)").sig("Get(m, ctx, \"f\")", "Get(m, \"f\", ctx)")), true)
        assertTrue(myFixture.editor.document.text.contains("\tGet(id string, ctx context.Context) (IhItem, error)\n}"))
    }

    fun testGeneratedMockIsConflict() {
        val generated = """
            // Code generated by mockery. DO NOT EDIT.

            package ih

            import "context"

            type ihGenMock struct{}

            func (m *ihGenMock) Get(ctx context.Context, id string) (IhItem, error) { return IhItem{}, nil }
            """
        val target = fixture(extra = mapOf("ihgen_mock.go" to generated))
        val message = conflicts { change(target) { it.copy(parameters = it.parameters + GoChangeParameter("opts", "IhOptions", -1, "IhOptions{}")) } }
        assertEquals("ihGenMock.Get is in a generated file (ihgen_mock.go); it is not changed: run go generate", message)
    }

    fun testGeneratedMockRefactorAnywayLeavesItAlone() {
        val generated = "// Code generated by mockery. DO NOT EDIT.\n\npackage ih\n\nimport \"context\"\n\ntype ihGenMock struct{}\n\n" +
            "func (m *ihGenMock) Get(ctx context.Context, id string) (IhItem, error) { return IhItem{}, nil }"
        val target = fixture(extra = mapOf("ihgen_mock.go" to generated))
        BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<Throwable> {
            change(target) { it.copy(parameters = it.parameters + GoChangeParameter("opts", "IhOptions", -1, "IhOptions{}")) }
        }
        myFixture.checkResult("ihgen_mock.go", go(generated), true)
        assertTrue(myFixture.editor.document.text.contains("s.next.Get(ctx, id, opts)"))
    }

    fun testRenameClashingWithFieldIsConflict() {
        val target = fixture()
        val message = conflicts { change(target) { it.copy(name = "cache") } }
        assertEquals("Type ihCachedStore already has a field cache", message)
    }

    fun testMisfitTypeUsedAsInterfaceIsConflict() {
        val legacy = "package ih\n\ntype ihLegacy struct{}\n\nfunc (ihLegacy) Get(id string) (IhItem, error) { return IhItem{}, nil }\n\nvar ihOld IhStore = ihLegacy{}"
        val target = fixture(extra = mapOf("ihlegacy.go" to legacy))
        val message = conflicts { change(target) { it.copy(parameters = it.parameters + GoChangeParameter("opts", "IhOptions", -1, "IhOptions{}")) } }
        assertEquals("Type ihLegacy has a method Get of another signature but is used as IhStore here; it does not implement it", message)
    }

    fun testUntickedHierarchyKeepsV1() {
        val target = fixture()
        val message = conflicts { change(target) { it.copy(parameters = it.parameters + GoChangeParameter("opts", "IhOptions", -1, "IhOptions{}"), hierarchy = false) } }
        assertEquals("IhStore.Get is an interface method: its implementations and calls through other interfaces are not changed", message)
        BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<Throwable> {
            change(target) { it.copy(parameters = it.parameters + GoChangeParameter("opts", "IhOptions", -1, "IhOptions{}"), hierarchy = false) }
        }
        checkFiles(
            store.sig("Get(ctx context.Context, id string) (IhItem, error)\n", "Get(ctx context.Context, id string, opts IhOptions) (IhItem, error)\n")
                .sig("s.next.Get(ctx, id)", "s.next.Get(ctx, id, IhOptions{})"),
            mocks,
            callers.sig("s.Get(ctx, \"a\")", "s.Get(ctx, \"a\", IhOptions{})"),
        )
    }

    // --- Add Method to Interface ---

    private fun addMethod(signature: String, delegate: Boolean = true, extra: Map<String, String> = emptyMap()) {
        fixture(extra = extra)
        val iface = GoAddInterfaceMethod.interfaceAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)!!
        GoAddInterfaceMethodProcessor(project, iface, GoAddMethodOptions.of(signature, delegate)).run()
    }

    private val deleteSpec = "Delete(ctx context.Context, id string) error"

    private fun withStubs(delegate: Boolean): Triple<String, String, String> {
        val wrapper = if (delegate) "return s.next.Delete(ctx, id)" else "panic(\"not implemented\")"
        val s = store.sig("(IhItem, error)\n}", "(IhItem, error)\n    $deleteSpec\n}")
            .sig("    return s.next.Get(ctx, id)\n}\n", "    return s.next.Get(ctx, id)\n}\n\nfunc (s *ihCachedStore) $deleteSpec {\n    $wrapper\n}\n")
            .trimEnd() + "\n\nfunc (svc ihService) $deleteSpec {\n    panic(\"not implemented\")\n}\n"
        val m = mocks.sig("    panic(\"not implemented\")\n}\n", "    panic(\"not implemented\")\n}\n\nfunc (m *ihStoreMock) $deleteSpec {\n    panic(\"not implemented\")\n}\n")
            .trimEnd() + "\n\nfunc (m ihServiceMock) $deleteSpec {\n    panic(\"not implemented\")\n}\n"
        return Triple(s, m, callers)
    }

    fun testAddMethodStubsAndDelegation() {
        addMethod(deleteSpec)
        val (s, m, c) = withStubs(delegate = true)
        checkFiles(s, m, c)
    }

    fun testAddMethodWithoutDelegation() {
        addMethod(deleteSpec, delegate = false)
        val (s, m, c) = withStubs(delegate = false)
        checkFiles(s, m, c)
    }

    fun testAddMethodImportsTheInterfaceFile() {
        myFixture.configureByText("ihtime.go", go("package ih\n\ntype ihClock interface {\n    <caret>Now() int\n}\n\ntype ihFixed struct{}\n\nfunc (f ihFixed) Now() int { return 0 }"))
        val iface = GoAddInterfaceMethod.interfaceAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)!!
        GoAddInterfaceMethodProcessor(project, iface, GoAddMethodOptions.of("Since(t time.Time) time.Duration")).run()
        assertEquals(
            go(
                "package ih\n\nimport \"time\"\n\ntype ihClock interface {\n    Now() int\n    Since(t time.Time) time.Duration\n}\n\ntype ihFixed struct{}\n\n" +
                    "func (f ihFixed) Now() int { return 0 }\n\nfunc (f ihFixed) Since(t time.Time) time.Duration {\n    panic(\"not implemented\")\n}",
            ),
            myFixture.editor.document.text,
        )
    }

    fun testAddMethodExistingMethodIsConflict() {
        val extra = mapOf("ihextra.go" to "package ih\n\nfunc (svc ihService) Delete() {}")
        val message = conflicts { addMethod(deleteSpec, extra = extra) }
        assertEquals("Type ihService already has a method or field Delete; no stub is added", message)
    }

    fun testAddMethodRefusesDuplicateAndGarbage() {
        fixture()
        val iface = GoAddInterfaceMethod.interfaceAt(myFixture.file.findElementAt(myFixture.caretOffset)!!) as GoTypeSpec
        assertEquals("Interface IhStore already has a method Get", GoAddInterfaceMethod.validate(iface, GoAddMethodOptions.of("Get()")))
        assertNotNull(GoAddInterfaceMethod.validate(iface, GoAddMethodOptions.of("not a method")))
        assertNull(GoAddInterfaceMethod.validate(iface, GoAddMethodOptions.of(deleteSpec)))
    }

    fun testAddMethodFromStructuredOptions() {
        fixture()
        val iface = GoAddInterfaceMethod.interfaceAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)!!
        val options = GoAddMethodOptions("Delete", listOf(GoChangeParameter("ctx", "context.Context"), GoChangeParameter("id", "string")), listOf(GoChangeResult("", "error")))
        GoAddInterfaceMethodProcessor(project, iface, options).run()
        val (s, m, c) = withStubs(delegate = true)
        checkFiles(s, m, c)
    }

    fun testAddMethodStructuredImports() {
        myFixture.configureByText("ihtime2.go", go("package ih\n\ntype ihClock2 interface {\n    <caret>Now() int\n}"))
        val iface = GoAddInterfaceMethod.interfaceAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)!!
        val options = GoAddMethodOptions("Wait", listOf(GoChangeParameter("ctx", "context.Context"), GoChangeParameter("d", "time.Duration")), listOf(GoChangeResult("", "error")))
        GoAddInterfaceMethodProcessor(project, iface, options).run()
        assertEquals(
            go("package ih\n\nimport (\n    \"context\"\n    \"time\"\n)\n\ntype ihClock2 interface {\n    Now() int\n    Wait(ctx context.Context, d time.Duration) error\n}"),
            myFixture.editor.document.text,
        )
    }

    fun testAddMethodRefusesBadType() {
        fixture()
        val iface = GoAddInterfaceMethod.interfaceAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)!!
        val badParam = GoAddMethodOptions("Delete", listOf(GoChangeParameter("id", "map[string")))
        assertEquals("Parameter id: 'map[string' is not a Go type", GoAddInterfaceMethod.validate(iface, badParam))
        val badResult = GoAddMethodOptions("Delete", results = listOf(GoChangeResult("", "func(")))
        assertEquals("Result #1: 'func(' is not a Go type", GoAddInterfaceMethod.validate(iface, badResult))
        val variadic = GoAddMethodOptions("Delete", listOf(GoChangeParameter("ids", "...string"), GoChangeParameter("n", "int")))
        assertEquals("Only the last parameter can be variadic", GoAddInterfaceMethod.validate(iface, variadic))
        assertNull(GoAddInterfaceMethod.validate(iface, GoAddMethodOptions("Delete", listOf(GoChangeParameter("ids", "...string")))))
    }

    fun testAddMethodContextListsImplementations() {
        fixture()
        val iface = GoAddInterfaceMethod.interfaceAt(myFixture.file.findElementAt(myFixture.caretOffset)!!)!!
        val context = GoAddInterfaceMethod.context(iface)
        assertEquals("IhStore", context.interfaceName)
        assertEquals(setOf("Get"), context.methods)
        assertEquals(
            listOf("ihServiceMock ihmocks.go ", "ihStoreMock ihmocks.go ", "ihCachedStore ihstore.go wrapper: field next", "ihService ihstore.go "),
            context.targets.map { "${it.name} ${it.file} ${it.note}" },
        )
    }

    fun testIntentionAvailableOnInterfaceName() {
        myFixture.configureByText("ihint.go", go("package ih\n\ntype Ih<caret>Named interface {\n    M()\n}"))
        assertEquals(1, myFixture.filterAvailableIntentions("Add method to interface").size)
        myFixture.configureByText("ihint2.go", go("package ih\n\ntype Ih<caret>Plain struct{}"))
        assertEquals(0, myFixture.filterAvailableIntentions("Add method to interface").size)
    }

    fun testGateOff() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        myFixture.configureByText("ihgate.go", go("package ih\n\ntype Ih<caret>Gated interface {\n    M()\n}"))
        assertEquals(0, myFixture.filterAvailableIntentions("Add method to interface").size)
        assertNull(GoChangeSignatureHandler().findTargetMember(myFixture.file, myFixture.editor))
    }
}
