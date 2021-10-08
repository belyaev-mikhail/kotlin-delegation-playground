package ru.spbstu

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyParameterDeclarationsFrom
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.backend.common.ir.passTypeArgumentsFrom
import org.jetbrains.kotlin.backend.common.ir.remapTypeParameters
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name

class ErasableDelegateCompiler(
    val context: IrPluginContext,
    val messageCollector: MessageCollector)  {

    operator fun invoke(declaration: IrClass) {
        messageCollector.report(CompilerMessageSeverity.WARNING, declaration.dumpKotlinLike())

        val field = declaration.properties.singleOrNull { it.backingField != null }
        field ?: error("Erasable delegate ${declaration.kotlinFqName.asString()} must have a single field")

        val getValue = declaration.findDeclaration<IrSimpleFunction> {
            it.name == Name.identifier("getValue") && it.isOperator
        }
        getValue
            ?: error("No getValue operator function found for erasable delegate class ${declaration.kotlinFqName.asString()}")

        val getterArgType = context.irBuiltIns.function(0).typeWith(field.type)
        val setterArgType = context.irBuiltIns.function(1).typeWith(field.type, context.irBuiltIns.unitType)
        val getValueImpl = declaration.addFunction {
            updateFrom(getValue)
            isOperator = false
            modality = Modality.FINAL
            isInline = true
            name = Name.identifier("getValue-impl")
            returnType = getValue.returnType
        }.apply {
            val getValueImpl = this
            copyParameterDeclarationsFrom(getValue)
            copyTypeParametersFrom(declaration)
            dispatchReceiverParameter = null

            body = getValue.body?.deepCopyWithSymbols(this, object: DeepCopySymbolRemapper() {
                val valueParameterSymbols = getValue.valueParameters.withIndex().associate { (i, v) -> v.symbol to i }

                override fun getReferencedFunction(symbol: IrFunctionSymbol): IrFunctionSymbol {
                    if (symbol == getValue.symbol) return getValueImpl.symbol
                    return super.getReferencedFunction(symbol)
                }

                override fun getReferencedValue(symbol: IrValueSymbol): IrValueSymbol {
                    if (symbol in valueParameterSymbols)
                        return getValueImpl.valueParameters[valueParameterSymbols[symbol]!!].symbol
                    return super.getReferencedValue(symbol)
                }
            })

            val parameterRemapping = declaration.typeParameters
                .zip(typeParameters.drop(getValue.typeParameters.size))
                .toMap()

            returnType = returnType.remapTypeParameters(getValue, this, parameterRemapping)
            remapTypes(IrTypeParameterRemapper(parameterRemapping))

            val getterP = addValueParameter {
                name = Name.identifier("getter")
                type = getterArgType
            }
            val setterP = addValueParameter {
                name = Name.identifier("setter")
                type = setterArgType
            }

            accept(object : IrElementTransformerVoidWithContext() {
                fun doGet(): IrExpression {
                    return currentScope!!.buildAStatement(context) {
                        irMemberCallByName(
                            irGet(getterP),
                            "invoke",
                            field.type
                        )
                    }
                }

                fun doSet(argument: IrExpression): IrExpression {
                    return currentScope!!.buildAStatement(context) {
                        irMemberCallByName(
                            irGet(setterP),
                            "invoke",
                            irUnit().type,
                            argument
                        )
                    }
                }

                override fun visitFunctionNew(declaration: IrFunction): IrStatement {
                    declaration.transformChildren(this, null)
                    return declaration
                }

                override fun visitCall(expression: IrCall): IrExpression {
                    if (expression.symbol == field.setter?.symbol) {
                        return doSet(expression.getValueArgument(0)!!)
                    }
                    if (expression.symbol == field.getter?.symbol) {
                        return doGet()
                    }
                    return super.visitCall(expression)
                }

                override fun visitGetField(expression: IrGetField): IrExpression {
                    return doGet()
                }

                override fun visitSetField(expression: IrSetField): IrExpression {
                    return doSet(expression.value)
                }

            }, null)

        }
        getValue.buildBlockBody(context) {
            val getterLambda = irLambda(getterArgType) {
                buildExpressionBody(context) {
                    when (val getter = field.getter) {
                        null -> {
                            irGetField(irGet(getValue.dispatchReceiverParameter!!), field.backingField!!)
                        }
                        else -> {
                            irCall(getter).apply {
                                dispatchReceiver = irGet(getValue.dispatchReceiverParameter!!)
                            }
                        }
                    }
                }
            }

            val setterLambda = irLambda(setterArgType) {
                val param = addValueParameter {
                    name = Name.identifier("value")
                    type = field.type
                }
                buildBlockBody(context) {
                    when (val setter = field.setter) {
                        null -> {
                            +irSetField(
                                irGet(getValue.dispatchReceiverParameter!!),
                                field.backingField!!,
                                irGet(param)
                            )
                        }
                        else -> {
                            +irCall(setter).apply {
                                dispatchReceiver = irGet(getValue.dispatchReceiverParameter!!)
                                valueArguments(irGet(param))
                            }
                        }
                    }

                }
            }
            +irReturn(
                irCall(getValueImpl.symbol, getValue.returnType).apply {
                    passTypeArgumentsFrom(getValue)
                    passTypeArgumentsFrom(declaration, getValue.typeParameters.size)
                    valueArguments(irArgs() + getterLambda + setterLambda)
                }
            )
        }

        messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, declaration.dumpKotlinLike())

    }
}
