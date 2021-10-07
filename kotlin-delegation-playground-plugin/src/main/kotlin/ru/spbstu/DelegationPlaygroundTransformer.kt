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
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.*
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.reflect.KFunction

object DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN :
    IrDeclarationOriginImpl("DELEGATION_PLAYGROUND_PLUGIN_GENERATED", true)

fun <F> nameOf(f: F): Name
    where F : KFunction<*>, F : () -> Any? {
    return Name.identifier(f.name)
}

fun <F> nameOf(f: F, dis: Unit = Unit): Name
    where F : KFunction<*>, F : (Nothing) -> Any? {
    return Name.identifier(f.name)
}

fun <F> nameOf(f: F, dis: Unit = Unit, dis2: Unit = Unit): Name
    where F : KFunction<*>, F : (Nothing, Nothing) -> Any? {
    return Name.identifier(f.name)
}

fun <F> nameOf(f: F, dis: Unit = Unit, dis2: Unit = Unit, dis3: Unit = Unit): Name
    where F : KFunction<*>, F : (Nothing, Nothing, Nothing) -> Any? {
    return Name.identifier(f.name)
}

operator fun FqName.plus(name: Name) = child(name)

const val thisPackage = "ru.spbstu"
val thisPackageFqName = FqName(thisPackage)

class DelegationPlaygroundTransformer(
    private val file: IrFile,
    private val fileSource: String,
    private val context: IrPluginContext,
    private val messageCollector: MessageCollector,
    private val annotations: Set<FqName>
) : IrElementTransformerVoidWithContext() {
    private val pluginGeneratedFunction = context.referenceFunctions(
        thisPackageFqName + nameOf(::pluginGenerated)
    ).singleOrNull()

    private val lazyDelegateFunctions = context.referenceFunctions(
        thisPackageFqName + Name.identifier("lazyDelegate")
    )

    private val proxyDelegateFunction = context.referenceFunctions(
        thisPackageFqName + nameOf(::proxyDelegate)
    ).firstOrNull()

    private val mixinFunctions = context.referenceFunctions(
        thisPackageFqName + Name.identifier("mixin")
    )

    @OptIn(ExperimentalStdlibApi::class)
    private val controlledFunctions = buildSet {
        if (pluginGeneratedFunction !== null)
            add(pluginGeneratedFunction)
        addAll(lazyDelegateFunctions)
        if (proxyDelegateFunction != null)
            add(proxyDelegateFunction)
        addAll(mixinFunctions)
    }

    val lazyDelegates = LazyDelegateAdapter(context)
    val proxyDelegates = ProxyDelegateAdapter(context)
    val mixinDelegates = MixinDelegateAdapter(context)

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    override fun visitClassNew(declaration: IrClass): IrStatement {
        if (declaration.hasAnnotation(FqName(ErasableDelegate::class))) {
            visitErasableDelegate(declaration)
        }

        val possibleDelegates = declaration.fields.filter {
            when {
                it.origin != IrDeclarationOrigin.DELEGATE -> false
                else -> it.delegateInitializerCall?.symbol in controlledFunctions
            }
        }.toList()
        if (possibleDelegates.isEmpty()) return super.visitClassNew(declaration)
        val delegateInfo: Map<IrFieldSymbol, IrClassSymbol> =
            declaration.declarations.filter {
                it.origin == IrDeclarationOrigin.DELEGATED_MEMBER
            }.map {
                when (it) {
                    is IrSimpleFunction -> it
                    is IrProperty -> it.getter!!
                    else -> error("Don't know how to handle delegated entity ${it.dumpKotlinLike()}")
                }
            }.associateBy(
                keySelector = {
                    it.findFirstChild<IrGetField>()?.symbol
                        ?: error("Cannot determine overriden interface for function ${it.name.asString()}")
                },
                valueTransform = {
                    val candidateInterfaces = it.overriddenSymbols.mapNotNullTo(mutableSetOf()) {
                        it.owner.dispatchReceiverParameter?.type?.takeIf { it.isInterface() }?.classOrNull
                    }
                    if (candidateInterfaces.size != 1) error("Cannot determine overriden interface for function ${it.name.asString()}")
                    candidateInterfaces.single()
                }
            )

        for (delegate in possibleDelegates)
            try {
                if (delegate.delegateInitializerCall!!.symbol == proxyDelegateFunction) {
                    proxyDelegates(declaration, delegateInfo[delegate.symbol]!!)
                    declaration.declarations.remove(delegate)
                }
                if (delegate.delegateInitializerCall!!.symbol in lazyDelegateFunctions) {
                    lazyDelegates(declaration, delegate, delegateInfo[delegate.symbol]!!)
                    declaration.declarations.remove(delegate)
                }
                if (delegate.delegateInitializerCall!!.symbol in mixinFunctions) {
                    mixinDelegates(declaration, delegate, delegateInfo[delegate.symbol]!!)
                    // do not remove original property for mixin for good housekeeping
                }
            } catch (ex: IllegalArgumentException) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    ex.message ?: "Unknown error",
                    delegate.location
                )
                throw ex
            } catch (ex: IllegalStateException) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    ex.message ?: "Unknown error",
                    delegate.location
                )
                throw ex
            }

        return super.visitClassNew(declaration)
    }

    private fun visitErasableDelegate(declaration: IrClass) {
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
            body = getValue.body?.patchDeclarationParents(this)


            copyParameterDeclarationsFrom(getValue)
            copyTypeParametersFrom(declaration)

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
                addValueParameter {
                    name = Name.identifier("value")
                    type = field.type
                }
                buildBlockBody(context) {
                    when (val setter = field.setter) {
                        null -> {
                            +irSetField(
                                irGet(getValue.dispatchReceiverParameter!!),
                                field.backingField!!,
                                irArg(0)
                            )
                        }
                        else -> {
                            +irCall(setter).apply {
                                dispatchReceiver = irGet(getValue.dispatchReceiverParameter!!)
                                valueArguments(irArg(0))
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

        messageCollector.report(CompilerMessageSeverity.ERROR, declaration.dumpKotlinLike())

    }

}


