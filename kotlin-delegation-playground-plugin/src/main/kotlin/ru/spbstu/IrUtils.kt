package ru.spbstu

import org.jetbrains.kotlin.backend.jvm.ir.needsAccessor
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.IrFunctionBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

fun IrBuilderWithScope.irGetProperty(receiver: IrExpression, property: IrProperty): IrExpression {
    // In some JVM-specific cases, such as when 'allopen' compiler plugin is applied,
    // data classes and corresponding properties can be non-final.
    // We should use getters for such properties (see KT-41284).
    val backingField = property.backingField
    return if (property.modality == Modality.FINAL && backingField != null) {
        irGetField(receiver, backingField)
    } else {
        irCall(property.getter!!).apply {
            dispatchReceiver = receiver
        }
    }
}

fun IrBuilderWithScope.irMemberCall(
    function: IrFunction,
    receiver: IrExpression,
    vararg arguments: IrExpression,
    type: IrType? = null
): IrFunctionAccessExpression = irCall(function.symbol, type ?: function.returnType).apply {
    dispatchReceiver = receiver
    for (i in 0..arguments.lastIndex) {
        putValueArgument(i, arguments[i])
    }
}

fun IrBuilderWithScope.irMemberCall(
    function: IrFunctionSymbol,
    receiver: IrExpression,
    vararg arguments: IrExpression
): IrFunctionAccessExpression = irCall(function).apply {
    dispatchReceiver = receiver
    for (i in 0..arguments.lastIndex) {
        putValueArgument(i, arguments[i])
    }
}

fun IrDeclarationParent.containingFunction(): IrFunction {
    return when (val parent = this) {
        is IrFunction -> parent
        !is IrDeclaration -> throw IllegalStateException()
        else -> parent.parent.containingFunction()
    }
}

fun IrBuilderWithScope.irThis(function: IrFunction? = null): IrExpression {
    val irFunction = function ?: parent.containingFunction()
    val irDispatchReceiverParameter = irFunction.dispatchReceiverParameter!!
    return IrGetValueImpl(
        startOffset, endOffset,
        irDispatchReceiverParameter.type,
        irDispatchReceiverParameter.symbol
    )
}

fun IrBuilderWithScope.irKClassReference(classType: IrType): IrClassReference =
    IrClassReferenceImpl(
        startOffset, endOffset,
        context.irBuiltIns.kClassClass.typeWith(classType),
        classType.classOrNull!!,
        classType
    )

fun IrBuilderWithScope.irArg(number: Int, irFunction: IrFunction = parent.containingFunction()): IrExpression {
    val parameter = irFunction.valueParameters[number]
    return IrGetValueImpl(
        startOffset, endOffset,
        parameter.type,
        parameter.symbol
    )
}

fun IrBuilderWithScope.irFirst(function: IrFunction = parent.containingFunction()): IrExpression = irArg(0, function)

fun <T : IrElement> IrStatementsBuilder<T>.irVariable(
    parent: IrDeclarationParent? = this.parent,
    startOffset: Int = this.startOffset,
    endOffset: Int = this.endOffset,
    origin: IrDeclarationOrigin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN,
    name: Name,
    isMutable: Boolean = false,
    isConst: Boolean = false,
    isLateinit: Boolean = false,
    initializer: IrExpression? = null,
    type: IrType? = null
): IrVariable {
    require(initializer != null || type != null)
    val result = buildVariable(
        parent = parent,
        startOffset = startOffset,
        endOffset = endOffset,
        origin = origin,
        name = name,
        type = type ?: initializer!!.type,
        isVar = isMutable,
        isConst = isConst,
        isLateinit = isLateinit
    )
    initializer?.let { result.initializer = initializer }
    +result
    return result
}

fun IrFunction.buildBlockBody(
    context: IrGeneratorContext,
    startOffset: Int = this.startOffset,
    endOffset: Int = this.endOffset,
    building: IrBlockBodyBuilder.() -> Unit
) {
    this.body = IrBlockBodyBuilder(context, Scope(this.symbol), startOffset, endOffset).blockBody(building)
}

class IrIfBuilder(
    private val builder: IrBuilderWithScope,
    private val condition: IrExpression,
    private var then_: IrBranch? = null,
    private var else_: IrBranch? = null,
    private val type: IrType? = null,
    private val origin: IrStatementOrigin? = null,
    private val startOffset: Int = builder.startOffset,
    private val endOffset: Int = builder.endOffset,
) {
    fun irThen(expr: IrExpression) {
        then_ = (builder.irBranch(condition, expr))
    }
    fun irElse(expr: IrExpression) {
        else_ = (builder.irElseBranch(expr))
    }

    fun irThen(body: IrBlockBuilder.() -> Unit) {
        irThen(builder.irBlock(startOffset, endOffset, origin, null, body))
    }
    fun irElse(body: IrBlockBuilder.() -> Unit) {
        irElse(builder.irBlock(startOffset, endOffset, origin, null, body))
    }

    private fun calcType(): IrType = when {
        type != null -> type
        else_ == null -> builder.context.irBuiltIns.unitType
        else -> then_?.result?.type!!
    }

    fun build() = IrIfThenElseImpl(startOffset, endOffset, calcType(), origin).apply {
        then_?.let { branches.add(it) }
        else_?.let { branches.add(it) }
    }
}

fun IrBuilderWithScope.irIf(condition: IrExpression,
                            type: IrType? = null,
                            origin: IrStatementOrigin? = null,
                            startOffset: Int = this.startOffset,
                            endOffset: Int = this.endOffset,
                            body: IrIfBuilder.() -> Unit): IrExpression {
    val builder = IrIfBuilder(this, condition, type = type, origin = origin, startOffset = startOffset, endOffset = endOffset)
    builder.body()
    return builder.build()
}

@OptIn(ExperimentalStdlibApi::class)
fun IrBuilderWithScope.irFunctionReference(
    to: IrSimpleFunction,
    origin: IrStatementOrigin? = null,
    startOffset: Int = this.startOffset,
    endOffset: Int = this.endOffset): IrFunctionReference {

    val typeArgs = buildList {
        val dispatchReceiver = to.dispatchReceiverParameter
        if (dispatchReceiver != null) add(dispatchReceiver.type)
        val extensionReceiver = to.extensionReceiverParameter
        if (extensionReceiver != null) add(extensionReceiver.type)
        for (vp in to.valueParameters) {
            add(vp.type)
        }
        add(to.returnType)
    }

    val kFunctionClass = if (to.isSuspend) {
        context.irBuiltIns.functionFactory.kSuspendFunctionN(typeArgs.size - 1)
    } else {
        context.irBuiltIns.functionFactory.kFunctionN(typeArgs.size - 1)
    }

    return IrFunctionReferenceImpl(
        startOffset,
        endOffset,
        kFunctionClass.typeWith(typeArgs),
        to.symbol,
        to.typeParameters.size,
        to.valueParameters.size,
        to.symbol,
        origin
    )
}

@OptIn(ExperimentalStdlibApi::class)
fun IrBuilderWithScope.irPropertyReference(
    to: IrProperty,
    origin: IrStatementOrigin? = null,
    startOffset: Int = this.startOffset,
    endOffset: Int = this.endOffset): IrPropertyReference {

    val typeArgs = buildList {
        when(val drp = to.getter?.dispatchReceiverParameter) {
            null -> {}
            else -> add(drp.type)
        }
        when(val erp = to.getter?.extensionReceiverParameter) {
            null -> {}
            else -> add(erp.type)
        }
        add(to.getter?.returnType ?: to.backingField?.type!!)
    }

    val kPropClass = context.irBuiltIns.getKPropertyClass(to.isVar, typeArgs.size - 1)

    return IrPropertyReferenceImpl(
        startOffset,
        endOffset,
        kPropClass.typeWith(typeArgs),
        to.symbol,
        to.getter?.typeParameters?.size ?: 0,
        to.backingField?.symbol,
        to.getter?.symbol,
        to.setter?.symbol,
        origin = IrStatementOrigin.PROPERTY_REFERENCE_FOR_DELEGATE
    )
}

inline fun IrProperty.addSetter(builder: IrFunctionBuilder.() -> Unit = {}): IrSimpleFunction =
    factory.buildFun {
        name = Name.special("<set-${this@addSetter.name}>")
        builder()
    }.also { setter ->
        this@addSetter.setter = setter
        setter.correspondingPropertySymbol = this@addSetter.symbol
        setter.parent = this@addSetter.parent
    }

