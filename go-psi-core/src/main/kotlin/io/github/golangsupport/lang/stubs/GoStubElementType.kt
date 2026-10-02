package io.github.golangsupport.lang.stubs

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import io.github.golangsupport.lang.GoLanguage
import org.jetbrains.annotations.ApiStatus

/**
 * Base of all Go stub element types. The external id is `go.<DEBUG_NAME>`, which is what the
 * `stubElementTypeHolder` registration (`externalIdPrefix="go."` on the generated `GoTypes`) expects:
 * the platform derives the id of every holder field from the prefix and the field name.
 */
@ApiStatus.Internal
abstract class GoStubElementType<S : StubElement<P>, P : PsiElement>(debugName: String) :
    IStubElementType<S, P>(debugName, GoLanguage) {

    final override fun getExternalId(): String = "go.$this"

    override fun shouldCreateStub(node: ASTNode): Boolean = GoStubPolicy.shouldCreateStub(node)

    override fun serialize(stub: S, dataStream: StubOutputStream) {}

    override fun indexStub(stub: S, sink: IndexSink) {}
}

/** Stub types whose stub carries no data. */
@ApiStatus.Internal
class GoSimpleStubElementType<S : StubElement<P>, P : PsiElement>(
    debugName: String,
    private val newStub: (StubElement<*>?, IStubElementType<*, *>) -> S,
    private val newPsi: (S, IStubElementType<*, *>) -> P,
) : GoStubElementType<S, P>(debugName) {

    override fun createPsi(stub: S): P = newPsi(stub, this)

    override fun createStub(psi: P, parentStub: StubElement<out PsiElement>?): S = newStub(parentStub, this)

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): S = newStub(parentStub, this)
}

/** Stub types whose stub carries only a name (parameters, fields, vars, consts, type parameters, ...). */
@ApiStatus.Internal
class GoNamedStubElementType<S : GoNamedStub<P>, P : io.github.golangsupport.lang.psi.GoNamedElement>(
    debugName: String,
    private val newStub: (StubElement<*>?, IStubElementType<*, *>, String?) -> S,
    private val index: (S, IndexSink) -> Unit = { _, _ -> },
    private val newPsi: (S, IStubElementType<*, *>) -> P,
) : GoStubElementType<S, P>(debugName) {

    override fun createPsi(stub: S): P = newPsi(stub, this)

    override fun createStub(psi: P, parentStub: StubElement<out PsiElement>?): S = newStub(parentStub, this, psi.name)

    override fun serialize(stub: S, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): S =
        newStub(parentStub, this, dataStream.readNameString())

    override fun indexStub(stub: S, sink: IndexSink) = index(stub, sink)
}

internal fun StubOutputStream.writeStrings(values: List<String>) {
    writeVarInt(values.size)
    values.forEach { writeUTFFast(it) }
}

internal fun StubInputStream.readStrings(): List<String> {
    val size = readVarInt()
    if (size == 0) return emptyList()
    return List(size) { readUTFFast() }
}

internal fun StubOutputStream.writeNullableUTF(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTFFast(value)
}

internal fun StubInputStream.readNullableUTF(): String? = if (readBoolean()) readUTFFast() else null
