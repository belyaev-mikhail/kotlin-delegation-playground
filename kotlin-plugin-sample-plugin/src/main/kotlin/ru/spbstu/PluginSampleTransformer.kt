/*
 * Copyright (C) 2020 Brian Norman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.spbstu

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.wasm.ir2wasm.allSuperInterfaces
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.fir.resolve.dfa.stackOf
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.utils.getSingleConstStringArgument
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object SAMPLE_PLUGIN_GENERATED_ORIGIN: IrDeclarationOriginImpl("SAMPLE_PLUGIN_GENERATED_ORIGIN", true)

class PluginSampleTransformer(
    private val file: IrFile,
    private val fileSource: String,
    private val context: IrPluginContext,
    private val messageCollector: MessageCollector,
    private val functions: Set<FqName>
) : IrElementTransformerVoidWithContext() {

    private val irBuiltins = context.irBuiltIns
    private val any = irBuiltins.anyClass.owner
    private val equals = any.functions.single { it.name == Name.identifier("equals") }
    private val hashCode = any.functions.single { it.name == Name.identifier("hashCode") }
    private val toString = any.functions.single { it.name == Name.identifier("toString") }

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

    fun IrDeclarationParent.containingFunction(): IrFunction {
        return when(val parent = this) {
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

    fun IrBuilderWithScope.irFirst(function: IrFunction? = null): IrExpression {
        val irFunction = function ?: parent.containingFunction()
        val irDispatchReceiverParameter = irFunction.valueParameters.first()
        return IrGetValueImpl(
            startOffset, endOffset,
            irDispatchReceiverParameter.type,
            irDispatchReceiverParameter.symbol
        )
    }

    fun <T: IrElement> IrStatementsBuilder<T>.irVariable(
        parent: IrDeclarationParent? = this.parent,
        startOffset: Int = UNDEFINED_OFFSET,
        endOffset: Int = UNDEFINED_OFFSET,
        origin: IrDeclarationOrigin = SAMPLE_PLUGIN_GENERATED_ORIGIN,
        name: Name,
        isVar: Boolean = false,
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
            isVar = isVar,
            isConst = isConst,
            isLateinit = isLateinit
        )
        initializer?.let { result.initializer = initializer }
        +result
        return result
    }

    fun IrFunction.buildBlockBody(context: IrGeneratorContext,
                                  startOffset: Int = UNDEFINED_OFFSET,
                                  endOffset: Int = UNDEFINED_OFFSET,
                                  building: IrBlockBodyBuilder.() -> Unit) {
        this.body = IrBlockBodyBuilder(context, Scope(this.symbol), startOffset, endOffset).blockBody(building)
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    override fun visitClassNew(declaration: IrClass): IrStatement {
        val annotation = declaration.getAnnotation(FqName("DataLike"))
            ?: return super.visitClassNew(declaration)

        val comparable = context.referenceClass(StandardNames.BUILT_INS_PACKAGE_FQ_NAME.child(Name.identifier("Comparable")))
        require(comparable != null)
        val actualComparable = comparable.typeWith(declaration.defaultType)

        if (declaration.superTypes.any { it.isSubtypeOf(actualComparable, context.irBuiltIns) }) {
            val compareTo = comparable.owner.functions.single { it.name == Name.identifier("compareTo") }
            val newCompareTo = declaration.overrideFunction(declaration.functions.first { it.overrides(compareTo) })

            newCompareTo.buildBlockBody(context) {
                val variable = irVariable(
                    name = Name.identifier("result"),
                    initializer = irInt(0),
                    isVar = true
                )
                for (property in declaration.properties) {
                    val prop = irGetProperty(irThis(), property)
                    val otherProp = irGetProperty(irFirst(newCompareTo), property)

                    val cmp = irCall(compareTo).apply {
                        dispatchReceiver = prop
                        putValueArgument(0, otherProp)
                    }
                    +irSet(variable.symbol, cmp)
                    +irIfThen(irBuiltins.unitType, irNotEquals(irGet(variable), irInt(0)), irReturn(irGet(variable)))
                }
                +irReturn(irInt(0))
            }
        }

        println(annotation.getArgumentsWithSymbols().map {
            "${it.first} = ${it.second.dump()}"
        })
        val dmg = MemberGenerator(context, declaration)

        val newEquals = declaration.overrideFunction(equals)

        dmg.generateEqualsMethod(
            newEquals,
            declaration.properties.toList()
        )

        val newHashCode = declaration.overrideFunction(hashCode)

        dmg.generateHashCodeMethod(
            newHashCode,
            declaration.properties.toList()
        )

        messageCollector.report(CompilerMessageSeverity.INFO,
            declaration.declarations.joinToString("\n") { it.dump() })

        val newToString = declaration.overrideFunction(toString)

        dmg.generateToStringMethod(newToString, declaration.properties.toList())

        return super.visitClassNew(declaration)
    }

}

private fun IrClass.overrideFunction(original: IrSimpleFunction): IrSimpleFunction {
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
        this.origin = SAMPLE_PLUGIN_GENERATED_ORIGIN
    }

    result.parent = this
    result.dispatchReceiverParameter = thisReceiver?.copyTo(result, origin = SAMPLE_PLUGIN_GENERATED_ORIGIN)
    result.valueParameters =
        existing.valueParameters.map { it.copyTo(result, origin = SAMPLE_PLUGIN_GENERATED_ORIGIN) }

    result.overriddenSymbols = existing.overriddenSymbols

    declarations.remove(existing)
    return result
}
