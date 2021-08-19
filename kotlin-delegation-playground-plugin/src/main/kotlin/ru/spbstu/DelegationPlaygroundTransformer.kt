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
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.analysis.cfa.FirReturnsImpliesAnalyzer.isSupertypeOf
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.lang.IllegalArgumentException
import kotlin.LazyThreadSafetyMode.NONE

object DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN : IrDeclarationOriginImpl("DELEGATION_PLAYGROUND_PLUGIN_GENERATED", true)

class DelegationPlaygroundTransformer(
    private val file: IrFile,
    private val fileSource: String,
    private val context: IrPluginContext,
    private val messageCollector: MessageCollector,
    private val annotations: Set<FqName>
) : IrElementTransformerVoidWithContext() {

    private val irBuiltins = context.irBuiltIns
    private val any = irBuiltins.anyClass.owner
    private val equals = any.functions.single { it.name == Name.identifier("equals") }
    private val hashCode = any.functions.single { it.name == Name.identifier("hashCode") }
    private val toString = any.functions.single { it.name == Name.identifier("toString") }


    private val mutableMapClass = context.referenceClass(StandardNames.FqNames.mutableMap)?.owner!!
    private val linkedMapOfFunction = context.referenceFunctions(
        StandardNames.COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("mutableMapOf"))
    ).find {
        it.owner.valueParameters.isEmpty()
    }!!
    private val mapPutFunction = mutableMapClass.findDeclaration<IrSimpleFunction> {
        it.name == Name.identifier("put")
    }!!
    private val mapStringAnyType = mutableMapClass.typeWith(
        context.irBuiltIns.stringType,
        context.irBuiltIns.anyNType
    )!!

    private val kclass = irBuiltins.kClassClass
    private val kClassQualifiedName = kclass.getPropertyGetter("qualifiedName")!!

    private val pluginGeneratedFunction = context.referenceFunctions(
        FqName("ru.spbstu.pluginGenerated")
    ).singleOrNull()

    private val lazyDelegateFunctions = context.referenceFunctions(
        FqName("ru.spbstu.lazyDelegate")
    )

    private val proxyDelegateFunction = context.referenceFunctions(
        FqName("ru.spbstu.proxyDelegate")
    ).firstOrNull()

    @OptIn(ExperimentalStdlibApi::class)
    private val controlledFunctions = buildSet {
        if (pluginGeneratedFunction !== null)
            add(pluginGeneratedFunction)
        addAll(lazyDelegateFunctions)
        if (proxyDelegateFunction != null)
            add(proxyDelegateFunction)
    }

    private val IrField.delegateInitializerCall
        get() = initializer?.expression as? IrCall

    fun checkCallMemberFunc(func: IrSimpleFunction): Boolean {

        val typeParams = func.typeParameters
        val valueParams = func.valueParameters

        func.name == Name.identifier("callMember") || return false
        typeParams.size == 1 || return false
        val (typeParam) = typeParams
        valueParams.size == 3 || return false
        val (thisRef, cref, args) = valueParams
        thisRef.type == irBuiltins.anyNType || return false
        irBuiltins.kFunctionClass.typeWith(typeParam.defaultType).isSubtypeOf(cref.type, irBuiltins) || return false
        mapStringAnyType.isSubtypeOf(args.type, irBuiltins) || return false

        return true
    }

    fun visitClassNewProxyDelegate(declaration: IrClass, type: IrType) {
        require(type.isInterface())
        val iface = type.classOrNull!!.owner

        val callMemberFunc by lazy(NONE) {
            val callMemberFunc = declaration.findDeclaration<IrSimpleFunction> { it.name == Name.identifier("callMember") }
            require(callMemberFunc != null && checkCallMemberFunc(callMemberFunc)) {
                """ |callMember should have exactly this signature:
                    |fun <T> callMember(thisRef: Any?, member: KCallable<T>, arguments: Map<String, Any?>): T
                    |""".trimMargin()
            }
            callMemberFunc
        }
        val getValueFunc by lazy(NONE) {
            val f = declaration.findDeclaration<IrSimpleFunction> {
                it.name == Name.identifier("getValue") && it.isOperator
            }
            requireNotNull(f) { "operator fun getValue() not found in class ${declaration.fqNameWhenAvailable}" }
        }
        val setValueFunc by lazy(NONE) {
            val f = declaration.findDeclaration<IrSimpleFunction> {
                it.name == Name.identifier("setValue") && it.isOperator
            }
            requireNotNull(f) { "operator fun setValue() not found in class ${declaration.fqNameWhenAvailable}" }
        }

        for (decl in iface.declarations) {
            if (decl is IrProperty && decl.isReal) {
                val newDecl = declaration.overrideProperty(decl)
                if (newDecl.getter != null) {
                    val getter = newDecl.getter!!
                    getter.buildBlockBody(context) {
                        val ref = irTemporary(irPropertyReference(newDecl), "ref")
                        +irReturn(
                            irMemberCall(
                                getValueFunc,
                                irThis(),
                                irGet(getter.extensionReceiverParameter ?: getter.dispatchReceiverParameter!!),
                                irGet(ref),
                                type = getter.returnType
                            ).apply {
                                if (getValueFunc.typeParameters.isNotEmpty())
                                    putTypeArgument(0, getter.returnType)
                            }
                        )
                    }
                }
                if (newDecl.setter != null) {
                    val setter = newDecl.setter!!
                    setter.buildBlockBody(context) {
                        val ref = irTemporary(irPropertyReference(newDecl), "ref")
                        +irReturn(
                            irMemberCall(
                                setValueFunc,
                                irThis(),
                                irGet(setter.extensionReceiverParameter ?: setter.dispatchReceiverParameter!!),
                                irGet(ref),
                                irFirst(),
                                type = irBuiltins.unitType
                            ).apply {
                                if (setValueFunc.typeParameters.isNotEmpty())
                                    putTypeArgument(0, setter.valueParameters.first().type)
                            }
                        )
                    }
                }
            }
            if (decl is IrSimpleFunction && decl.isReal) {
                val newDecl = declaration.overrideFunction(decl)
                newDecl.buildBlockBody(context) {
                    val ref = irTemporary(irFunctionReference(newDecl), "ref")
                    val argMap = irTemporary(irCall(linkedMapOfFunction, mapStringAnyType).apply {
                        putTypeArgument(0, irBuiltins.stringType)
                        putTypeArgument(1, irBuiltins.anyNType)
                    }, "argMap")
                    for (arg in newDecl.valueParameters) {
                        +irMemberCall(
                            mapPutFunction,
                            irGet(argMap),
                            irString(arg.name.asString()),
                            irGet(arg),
                            type = irBuiltins.anyNType
                        )
                    }

                    +irReturn(
                        irMemberCall(
                            callMemberFunc,
                            irGet(newDecl.dispatchReceiverParameter!!),
                            irGet(newDecl.extensionReceiverParameter ?: newDecl.dispatchReceiverParameter!!),
                            irGet(ref),
                            irGet(argMap)
                        ).apply { putTypeArgument(0, decl.returnType) }
                    )

                }
            }
        }
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    override fun visitClassNew(declaration: IrClass): IrStatement {
        val possibleDelegates = declaration.fields.filter {
            when {
                it.origin != IrDeclarationOrigin.DELEGATE -> false
                else -> it.delegateInitializerCall?.symbol in controlledFunctions
            }
        }.toList()
        if (possibleDelegates.isEmpty()) return super.visitClassNew(declaration)

        for(delegate in possibleDelegates)
            try {
                if (delegate.delegateInitializerCall!!.symbol == proxyDelegateFunction) {
                    visitClassNewProxyDelegate(declaration, delegate.type)
                    declaration.declarations.remove(delegate)
                }
            } catch (ex: IllegalArgumentException) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    ex.message ?: "Unknown error"
                )
            } catch (ex: IllegalStateException) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    ex.message ?: "Unknown error"
                )
            }

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

private fun IrClass.overrideProperty(original: IrProperty): IrProperty {
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
            this.dispatchReceiverParameter = this@overrideProperty.thisReceiver?.copyTo(this)
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
            this.dispatchReceiverParameter = this@overrideProperty.thisReceiver?.copyTo(this)
        }
    }

    declarations.remove(existing)
    return result
}