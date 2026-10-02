package io.github.golangsupport.lang.stubs.index

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * Base of the Go string stub indices. [version] is the index's own version; the platform also
 * rebuilds every stub index when `GoFileElementType.STUB_VERSION` changes.
 */
abstract class GoStringStubIndex<P : PsiElement>(
    private val key: StubIndexKey<String, P>,
    private val psiClass: Class<P>,
    private val version: Int,
) : StringStubIndexExtension<P>() {
    override fun getKey(): StubIndexKey<String, P> = key

    override fun getVersion(): Int = super.getVersion() + version

    fun find(name: String, project: Project, scope: GlobalSearchScope): Collection<P> =
        StubIndex.getElements(key, name, project, scope, psiClass)
}

/** Package name -> files (indexed from the file stub). */
class GoPackagesIndex : GoStringStubIndex<GoFile>(KEY, GoFile::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoFile> = StubIndexKey.createIndexKey("go.packages")
    }
}

/** Function name -> top-level function declarations. */
class GoFunctionIndex : GoStringStubIndex<GoFunctionDeclaration>(KEY, GoFunctionDeclaration::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoFunctionDeclaration> = StubIndexKey.createIndexKey("go.functions")
    }
}

/** Receiver base type name (`T` for `func (*T) M()`) -> method declarations. */
class GoMethodIndex : GoStringStubIndex<GoMethodDeclaration>(KEY, GoMethodDeclaration::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoMethodDeclaration> = StubIndexKey.createIndexKey("go.methods")
    }
}

/** Type name -> package-level type specs (including aliases). */
class GoTypesIndex : GoStringStubIndex<GoTypeSpec>(KEY, GoTypeSpec::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoTypeSpec> = StubIndexKey.createIndexKey("go.types")
    }
}

/** Exported name -> package-level functions, methods, types, vars and consts. */
class GoAllPublicNamesIndex : GoStringStubIndex<GoNamedElement>(KEY, GoNamedElement::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoNamedElement> = StubIndexKey.createIndexKey("go.all.public.names")
    }
}

/** Unexported name -> package-level functions, methods, types, vars and consts. */
class GoAllPrivateNamesIndex : GoStringStubIndex<GoNamedElement>(KEY, GoNamedElement::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoNamedElement> = StubIndexKey.createIndexKey("go.all.private.names")
    }
}

/** `name/arity` -> method declarations (for "implements" search). */
class GoMethodFingerprintIndex : GoStringStubIndex<GoMethodDeclaration>(KEY, GoMethodDeclaration::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoMethodDeclaration> = StubIndexKey.createIndexKey("go.method.fingerprint")
    }
}

/** `name/arity` -> interface method specs (for "implements" search). */
class GoMethodSpecFingerprintIndex : GoStringStubIndex<GoMethodSpec>(KEY, GoMethodSpec::class.java, 1) {
    companion object {
        @JvmField
        val KEY: StubIndexKey<String, GoMethodSpec> = StubIndexKey.createIndexKey("go.method.spec.fingerprint")
    }
}

/** Key of the fingerprint indices. */
fun goMethodFingerprint(name: String, arity: Int): String = "$name/$arity"
