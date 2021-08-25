package ru.spbstu

import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.defaultType

internal fun IrClass.overrideFunction(original: IrSimpleFunction): IrSimpleFunction {
    val existingIndex = declarations.indexOfFirst {
        it is IrFunction &&
            it.name == original.name &&
            (it.dispatchReceiverParameter == null) == (original.dispatchReceiverParameter == null) &&
            it.valueParameters.map { it.type } == original.valueParameters.map { it.type }
    }
    require(existingIndex != -1)
    val existing = declarations[existingIndex]
    require(existing is IrSimpleFunction)

    val result = addFunction {
        updateFrom(existing)
        this.name = existing.name
        this.returnType = existing.returnType
        this.modality = Modality.FINAL
        this.visibility = DescriptorVisibilities.PUBLIC
        this.isSuspend = false
        this.isFakeOverride = false
        this.origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
        this.startOffset = SYNTHETIC_OFFSET
        this.endOffset = SYNTHETIC_OFFSET
        this.isExternal = false
    }

    result.parent = this
    result.dispatchReceiverParameter = thisReceiver?.copyTo(
        result,
        type = this.defaultType,
        origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
    )
    result.valueParameters =
        existing.valueParameters.map { it.copyTo(result, origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN) }

    result.overriddenSymbols = existing.overriddenSymbols

    declarations.remove(existing)
    return result
}

internal fun IrClass.overrideProperty(original: IrProperty): IrProperty {
    val existingIndex = declarations.indexOfFirst {
        it is IrProperty && it.name == original.name &&
            (it.getter?.extensionReceiverParameter == null) == (it.getter?.extensionReceiverParameter == null)
    }
    require(existingIndex != -1)
    val existing = declarations[existingIndex]
    require(existing is IrProperty)

    val result = addProperty {
        updateFrom(existing)
        this.name = existing.name
        this.modality = Modality.FINAL
        this.visibility = DescriptorVisibilities.PUBLIC
        this.isFakeOverride = false
        this.origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
        this.startOffset = SYNTHETIC_OFFSET
        this.endOffset = SYNTHETIC_OFFSET
        this.isExternal = false
    }

    if (existing.getter != null) {
        result.addGetter {
            val existingGetter = existing.getter!!
            updateFrom(existingGetter)
            this.returnType = existingGetter.returnType
            this.modality = Modality.FINAL
            this.visibility = DescriptorVisibilities.PUBLIC
            this.isSuspend = false
            this.isFakeOverride = false
            this.origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
            this.startOffset = SYNTHETIC_OFFSET
            this.endOffset = SYNTHETIC_OFFSET
            this.isExternal = false

        }.apply {
            this.dispatchReceiverParameter = this@overrideProperty.thisReceiver?.copyTo(
                this,
                type = this@overrideProperty.defaultType,
                origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
            )
            overriddenSymbols = overriddenSymbols + existing.getter?.overriddenSymbols.orEmpty()
        }
    }

    if (existing.setter != null) {
        result.addSetter {
            val existingSetter = existing.setter!!
            updateFrom(existingSetter)
            this.returnType = existingSetter.returnType
            this.modality = Modality.FINAL
            this.visibility = DescriptorVisibilities.PUBLIC
            this.isSuspend = false
            this.isFakeOverride = false
            this.origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
            this.startOffset = SYNTHETIC_OFFSET
            this.endOffset = SYNTHETIC_OFFSET
            this.isExternal = false
        }.apply {
            this.dispatchReceiverParameter = this@overrideProperty.thisReceiver?.copyTo(
                this,
                type = this@overrideProperty.defaultType,
                origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
            )
            overriddenSymbols = overriddenSymbols + existing.setter?.overriddenSymbols.orEmpty()
        }
    }

    declarations.remove(existing)
    return result
}
