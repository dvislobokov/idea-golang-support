package io.github.golangsupport.lang.stubs

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.lang.psi.impl.GoPsiImplUtil
import io.github.golangsupport.lang.stubs.index.GoAllPrivateNamesIndex
import io.github.golangsupport.lang.stubs.index.GoAllPublicNamesIndex
import io.github.golangsupport.lang.stubs.index.GoFunctionIndex
import io.github.golangsupport.lang.stubs.index.GoMethodFingerprintIndex
import io.github.golangsupport.lang.stubs.index.GoMethodIndex
import io.github.golangsupport.lang.stubs.index.GoMethodSpecFingerprintIndex
import io.github.golangsupport.lang.stubs.index.GoTypesIndex
import io.github.golangsupport.lang.stubs.index.goMethodFingerprint
import org.jetbrains.annotations.ApiStatus

// Stub element types with data. Data-less and name-only types are built in GoElementTypeFactory from
// GoSimpleStubElementType / GoNamedStubElementType.

private typealias ParentStub = StubElement<out PsiElement>?

/** Package-level names go to the public or private all-names index. */
internal fun indexPackageLevelName(stub: GoNamedStub<*>, sink: IndexSink) {
    val name = stub.name ?: return
    if (name.isEmpty() || name == "_") return
    sink.occurrence(if (stub.isPublic) GoAllPublicNamesIndex.KEY else GoAllPrivateNamesIndex.KEY, name)
}

@ApiStatus.Internal
class GoImportSpecElementType(debugName: String) : GoStubElementType<GoImportSpecStub, GoImportSpec>(debugName) {
    override fun createPsi(stub: GoImportSpecStub): GoImportSpec =
        io.github.golangsupport.lang.psi.impl.GoImportSpecImpl(stub, this)

    override fun createStub(psi: GoImportSpec, parentStub: ParentStub): GoImportSpecStub =
        GoImportSpecStub(parentStub, this, psi.path, psi.alias)

    override fun serialize(stub: GoImportSpecStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.path)
        dataStream.writeName(stub.alias)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoImportSpecStub =
        GoImportSpecStub(parentStub, this, dataStream.readNameString() ?: "", dataStream.readNameString())
}

@ApiStatus.Internal
class GoFunctionDeclarationElementType(debugName: String) :
    GoStubElementType<GoFunctionDeclarationStub, GoFunctionDeclaration>(debugName) {
    override fun createPsi(stub: GoFunctionDeclarationStub): GoFunctionDeclaration =
        io.github.golangsupport.lang.psi.impl.GoFunctionDeclarationImpl(stub, this)

    override fun createStub(psi: GoFunctionDeclaration, parentStub: ParentStub): GoFunctionDeclarationStub =
        GoFunctionDeclarationStub(parentStub, this, psi.name, GoPsiImplUtil.arity(psi.signature))

    override fun serialize(stub: GoFunctionDeclarationStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeVarInt(stub.arity)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoFunctionDeclarationStub =
        GoFunctionDeclarationStub(parentStub, this, dataStream.readNameString(), dataStream.readVarInt())

    override fun indexStub(stub: GoFunctionDeclarationStub, sink: IndexSink) {
        val name = stub.name ?: return
        sink.occurrence(GoFunctionIndex.KEY, name)
        indexPackageLevelName(stub, sink)
    }
}

@ApiStatus.Internal
class GoMethodDeclarationElementType(debugName: String) :
    GoStubElementType<GoMethodDeclarationStub, GoMethodDeclaration>(debugName) {
    override fun createPsi(stub: GoMethodDeclarationStub): GoMethodDeclaration =
        io.github.golangsupport.lang.psi.impl.GoMethodDeclarationImpl(stub, this)

    override fun createStub(psi: GoMethodDeclaration, parentStub: ParentStub): GoMethodDeclarationStub {
        val receiver = GoPsiImplUtil.receiverBaseType(psi.receiver)
        return GoMethodDeclarationStub(
            parentStub, this, psi.name, GoPsiImplUtil.arity(psi.signature), receiver?.name, receiver?.pointer == true,
        )
    }

    override fun serialize(stub: GoMethodDeclarationStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeVarInt(stub.arity)
        dataStream.writeName(stub.receiverTypeName)
        dataStream.writeBoolean(stub.isPointerReceiver)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoMethodDeclarationStub =
        GoMethodDeclarationStub(
            parentStub, this, dataStream.readNameString(), dataStream.readVarInt(),
            dataStream.readNameString(), dataStream.readBoolean(),
        )

    override fun indexStub(stub: GoMethodDeclarationStub, sink: IndexSink) {
        val name = stub.name ?: return
        stub.receiverTypeName?.let { sink.occurrence(GoMethodIndex.KEY, it) }
        sink.occurrence(GoMethodFingerprintIndex.KEY, goMethodFingerprint(name, stub.arity))
        indexPackageLevelName(stub, sink)
    }
}

@ApiStatus.Internal
class GoParameterDeclarationElementType(debugName: String) :
    GoStubElementType<GoParameterDeclarationStub, GoParameterDeclaration>(debugName) {
    override fun createPsi(stub: GoParameterDeclarationStub): GoParameterDeclaration =
        io.github.golangsupport.lang.psi.impl.GoParameterDeclarationImpl(stub, this)

    override fun createStub(psi: GoParameterDeclaration, parentStub: ParentStub): GoParameterDeclarationStub =
        GoParameterDeclarationStub(parentStub, this, psi.node.findChildByType(GoTypes.ELLIPSIS) != null)

    override fun serialize(stub: GoParameterDeclarationStub, dataStream: StubOutputStream) {
        dataStream.writeBoolean(stub.isVariadic)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoParameterDeclarationStub =
        GoParameterDeclarationStub(parentStub, this, dataStream.readBoolean())
}

@ApiStatus.Internal
class GoTypeSpecElementType(debugName: String) : GoStubElementType<GoTypeSpecStub, GoTypeSpec>(debugName) {
    override fun createPsi(stub: GoTypeSpecStub): GoTypeSpec =
        io.github.golangsupport.lang.psi.impl.GoTypeSpecImpl(stub, this)

    override fun createStub(psi: GoTypeSpec, parentStub: ParentStub): GoTypeSpecStub {
        val node = psi.node
        return GoTypeSpecStub(
            parentStub, this, psi.name,
            node.findChildByType(GoTypes.ASSIGN) != null,
            node.findChildByType(GoTypes.TYPE_PARAMETERS) != null,
        )
    }

    override fun serialize(stub: GoTypeSpecStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeBoolean(stub.isAlias)
        dataStream.writeBoolean(stub.hasTypeParameters)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoTypeSpecStub =
        GoTypeSpecStub(parentStub, this, dataStream.readNameString(), dataStream.readBoolean(), dataStream.readBoolean())

    override fun indexStub(stub: GoTypeSpecStub, sink: IndexSink) {
        val name = stub.name ?: return
        sink.occurrence(GoTypesIndex.KEY, name)
        indexPackageLevelName(stub, sink)
    }
}

@ApiStatus.Internal
class GoConstraintTermElementType(debugName: String) :
    GoStubElementType<GoConstraintTermStub, GoConstraintTerm>(debugName) {
    override fun createPsi(stub: GoConstraintTermStub): GoConstraintTerm =
        io.github.golangsupport.lang.psi.impl.GoConstraintTermImpl(stub, this)

    override fun createStub(psi: GoConstraintTerm, parentStub: ParentStub): GoConstraintTermStub =
        GoConstraintTermStub(parentStub, this, psi.node.findChildByType(GoTypes.TILDE) != null)

    override fun serialize(stub: GoConstraintTermStub, dataStream: StubOutputStream) {
        dataStream.writeBoolean(stub.hasTilde)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoConstraintTermStub =
        GoConstraintTermStub(parentStub, this, dataStream.readBoolean())
}

/** All type nodes; see [GoTypeStub.detail]. */
// Note: these types are created while the generated GoTypes interface is still initializing, so
// GoTypes fields must not be read in constructors or initializers (they may still be null).

@ApiStatus.Internal
class GoTypeElementType(debugName: String, private val newPsi: (GoTypeStub, IStubElementType<*, *>) -> GoType) :
    GoStubElementType<GoTypeStub, GoType>(debugName) {
    override fun createPsi(stub: GoTypeStub): GoType = newPsi(stub, this)

    override fun createStub(psi: GoType, parentStub: ParentStub): GoTypeStub =
        GoTypeStub(parentStub, this, detail(psi))

    private fun detail(psi: GoType): String? {
        val node = psi.node
        return when (node.elementType) {
            GoTypes.ARRAY_OR_SLICE_TYPE -> {
                val open = node.findChildByType(GoTypes.LBRACK) ?: return null
                val close = node.findChildByType(GoTypes.RBRACK) ?: return null
                val start = open.textRange.endOffset - node.startOffset
                val end = close.textRange.startOffset - node.startOffset
                if (end <= start) null else node.text.substring(start, end).trim().ifEmpty { null }
            }
            GoTypes.CHANNEL_TYPE -> node.getChildren(null)
                .filter { it.elementType === GoTypes.CHAN || it.elementType === GoTypes.ARROW }
                .joinToString("") { it.text }
            else -> null
        }
    }

    override fun serialize(stub: GoTypeStub, dataStream: StubOutputStream) {
        dataStream.writeNullableUTF(stub.detail)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoTypeStub =
        GoTypeStub(parentStub, this, dataStream.readNullableUTF())
}

@ApiStatus.Internal
class GoTypeReferenceExpressionElementType(debugName: String) :
    GoStubElementType<GoTypeReferenceExpressionStub, GoTypeReferenceExpression>(debugName) {
    override fun createPsi(stub: GoTypeReferenceExpressionStub): GoTypeReferenceExpression =
        io.github.golangsupport.lang.psi.impl.GoTypeReferenceExpressionImpl(stub, this)

    override fun createStub(psi: GoTypeReferenceExpression, parentStub: ParentStub): GoTypeReferenceExpressionStub {
        val node = psi.node
        val name = node.findChildByType(GoTypes.IDENTIFIER)?.text ?: ""
        val qualifier = node.findChildByType(GoTypes.REFERENCE_EXPRESSION)?.text
        return GoTypeReferenceExpressionStub(parentStub, this, name, qualifier)
    }

    override fun serialize(stub: GoTypeReferenceExpressionStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeName(stub.qualifier)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoTypeReferenceExpressionStub =
        GoTypeReferenceExpressionStub(parentStub, this, dataStream.readNameString() ?: "", dataStream.readNameString())
}

@ApiStatus.Internal
class GoVarSpecElementType(debugName: String) : GoStubElementType<GoVarSpecStub, GoVarSpec>(debugName) {
    override fun createPsi(stub: GoVarSpecStub): GoVarSpec =
        io.github.golangsupport.lang.psi.impl.GoVarSpecImpl(stub, this)

    override fun createStub(psi: GoVarSpec, parentStub: ParentStub): GoVarSpecStub =
        GoVarSpecStub(parentStub, this, psi.expressionList.map { it.text })

    override fun serialize(stub: GoVarSpecStub, dataStream: StubOutputStream) {
        dataStream.writeStrings(stub.values)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoVarSpecStub =
        GoVarSpecStub(parentStub, this, dataStream.readStrings())
}

@ApiStatus.Internal
class GoConstSpecElementType(debugName: String) : GoStubElementType<GoConstSpecStub, GoConstSpec>(debugName) {
    override fun createPsi(stub: GoConstSpecStub): GoConstSpec =
        io.github.golangsupport.lang.psi.impl.GoConstSpecImpl(stub, this)

    override fun createStub(psi: GoConstSpec, parentStub: ParentStub): GoConstSpecStub {
        var iota = 0
        var sibling = psi.node.treePrev
        while (sibling != null) {
            if (sibling.elementType === GoTypes.CONST_SPEC) iota++
            sibling = sibling.treePrev
        }
        return GoConstSpecStub(parentStub, this, iota, psi.expressionList.map { it.text })
    }

    override fun serialize(stub: GoConstSpecStub, dataStream: StubOutputStream) {
        dataStream.writeVarInt(stub.iota)
        dataStream.writeStrings(stub.values)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoConstSpecStub =
        GoConstSpecStub(parentStub, this, dataStream.readVarInt(), dataStream.readStrings())
}

@ApiStatus.Internal
class GoAnonymousFieldDefinitionElementType(debugName: String) :
    GoStubElementType<GoAnonymousFieldDefinitionStub, GoAnonymousFieldDefinition>(debugName) {
    override fun createPsi(stub: GoAnonymousFieldDefinitionStub): GoAnonymousFieldDefinition =
        io.github.golangsupport.lang.psi.impl.GoAnonymousFieldDefinitionImpl(stub, this)

    override fun createStub(psi: GoAnonymousFieldDefinition, parentStub: ParentStub): GoAnonymousFieldDefinitionStub =
        GoAnonymousFieldDefinitionStub(parentStub, this, psi.name, psi.node.findChildByType(GoTypes.MUL) != null)

    override fun serialize(stub: GoAnonymousFieldDefinitionStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeBoolean(stub.isPointer)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoAnonymousFieldDefinitionStub =
        GoAnonymousFieldDefinitionStub(parentStub, this, dataStream.readNameString(), dataStream.readBoolean())
}

@ApiStatus.Internal
class GoTagElementType(debugName: String) : GoStubElementType<GoTagStub, GoTag>(debugName) {
    override fun createPsi(stub: GoTagStub): GoTag = io.github.golangsupport.lang.psi.impl.GoTagImpl(stub, this)

    override fun createStub(psi: GoTag, parentStub: ParentStub): GoTagStub = GoTagStub(parentStub, this, psi.text)

    override fun serialize(stub: GoTagStub, dataStream: StubOutputStream) {
        dataStream.writeUTFFast(stub.text)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoTagStub =
        GoTagStub(parentStub, this, dataStream.readUTFFast())
}

@ApiStatus.Internal
class GoMethodSpecElementType(debugName: String) : GoStubElementType<GoMethodSpecStub, GoMethodSpec>(debugName) {
    override fun createPsi(stub: GoMethodSpecStub): GoMethodSpec =
        io.github.golangsupport.lang.psi.impl.GoMethodSpecImpl(stub, this)

    override fun createStub(psi: GoMethodSpec, parentStub: ParentStub): GoMethodSpecStub =
        GoMethodSpecStub(parentStub, this, psi.name, GoPsiImplUtil.arity(psi.signature))

    override fun serialize(stub: GoMethodSpecStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeVarInt(stub.arity)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoMethodSpecStub =
        GoMethodSpecStub(parentStub, this, dataStream.readNameString(), dataStream.readVarInt())

    override fun indexStub(stub: GoMethodSpecStub, sink: IndexSink) {
        val name = stub.name ?: return
        sink.occurrence(GoMethodSpecFingerprintIndex.KEY, goMethodFingerprint(name, stub.arity))
    }
}
