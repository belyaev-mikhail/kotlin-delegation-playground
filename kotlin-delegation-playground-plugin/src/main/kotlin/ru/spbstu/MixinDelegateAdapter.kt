package ru.spbstu

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.addFakeOverrides
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

class MixinDelegateAdapter(val context: IrPluginContext) {
    val any by lazy {
        context.irBuiltIns.anyClass.owner
    }
    operator fun invoke(declaration: IrClass, delegate: IrField, iface: IrClassSymbol) {
        val ifaceType = declaration.superTypes.find { it.classOrNull == iface }!!
        val initCall = delegate.delegateInitializerCall!!

        val mixinClassType = when(initCall.typeArgumentsCount) {
            1 -> initCall.getTypeArgument(0)!!
            2 -> initCall.getTypeArgument(1)!!
            else -> error("")
        }
        delegate.buildInitializer(context) {
            irBlock {

                val anonymous = context.irFactory.buildClass {
                    name = SpecialNames.NO_NAME_PROVIDED
                    origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
                    visibility = DescriptorVisibilities.LOCAL
                }.apply makeClass@{
                    thisReceiver = buildReceiverParameter(this, IrDeclarationOrigin.INSTANCE_RECEIVER, typeWith())
                    superTypes = listOf(mixinClassType, ifaceType)
                    addConstructor {
                        origin = DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN
                        isPrimary = true
                    }.apply makeConstructor@{
                        buildBlockBody(context) {
                            +when {
                                !mixinClassType.isInterface() -> irDelegatingConstructorCall(mixinClassType.classOrNull?.owner?.primaryConstructor!!)
                                else -> irDelegatingConstructorCall(any.primaryConstructor!!)
                            }
                        }
                    }

                    addFakeOverrides(context.irBuiltIns)

                    val selfProp = properties.find { it.name == Name.identifier("self") }!!
                    overrideProperty(selfProp).apply {
                        getter!!.apply {

                            dispatchReceiverParameter = this@makeClass.thisReceiver?.copyTo(this)
                            buildBlockBody(context) { +irReturn(irGet(declaration.thisReceiver!!)) }
                        }
                    }
                    val ifaceFuncs = iface.functions
                    for (it in ifaceFuncs) {
                        val f = it.owner
                        if (f.isFakeOverride) continue
                        if (mixinClassType.classOrFail.owner.overrides(f)) continue
                        check (declaration
                            .overrideFor(f)
                            ?.takeIf { it.origin != IrDeclarationOrigin.DELEGATED_MEMBER } != null) {
                            "Neither mixin class nor declaration override function ${f.dumpKotlinLike()}"
                        }
                        val newFunc = overrideFunction(f)
                        newFunc.buildBlockBody(context) {
                            context.irFactory.buildProperty {
                                name = Name.identifier("Hello")
                            }
                            +irReturn(irCall(it).apply {
                                dispatchReceiver = irGet(declaration.thisReceiver!!)
                                valueArguments(irArgs())
                            })
                        }
                    }

                    val ifaceProps = iface.owner.properties
                    for (it in ifaceProps) {
                        val f = it.getter ?: continue
                        if (f.isFakeOverride) continue
                        if (mixinClassType.classOrFail.owner.overrides(it)) continue
                        check (declaration.overrides(it)) {
                            "Neither mixin class nor declaration override property ${it.dumpKotlinLike()}"
                        }

                        val newProp = overrideProperty(it)
                        newProp.getter?.buildBlockBody(context) {
                            +irReturn(irCall(it.getter!!.symbol).apply {
                                dispatchReceiver = irGet(declaration.thisReceiver!!)
                            })
                        }
                        newProp.setter?.buildBlockBody(context) {
                            irCall(it.setter!!.symbol).apply {
                                dispatchReceiver = irGet(declaration.thisReceiver!!)
                                valueArguments(irArgs())
                            }
                        }
                    }

                }
                +anonymous
                +irCall(anonymous.primaryConstructor!!.symbol)
            }.patchDeclarationParents(delegate)
        }
    }

}
