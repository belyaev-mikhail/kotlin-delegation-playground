package ru.spbstu

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isSubtypeOf
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.findDeclaration
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isReal
import org.jetbrains.kotlin.name.Name

class ProxyDelegateAdapter(val context: IrPluginContext) {
    private val mutableMapClass by lazy { context.referenceClass(StandardNames.FqNames.mutableMap)?.owner!! }
    private val linkedMapOfFunction by lazy {
        context.referenceFunctions(
            StandardNames.COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("mutableMapOf"))
        ).find {
            it.owner.valueParameters.isEmpty()
        }!!
    }
    private val mapPutFunction by lazy {
        mutableMapClass.findDeclaration<IrSimpleFunction> {
            it.name == Name.identifier("put")
        }!!
    }
    private val mapStringAnyType by lazy {
        mutableMapClass.typeWith(
            context.irBuiltIns.stringType,
            context.irBuiltIns.anyNType
        )!!
    }

    operator fun invoke(declaration: IrClass, type: IrClassSymbol) {
        val iface = type.owner

        val callMemberFunc by lazy(LazyThreadSafetyMode.NONE) {
            val callMemberFunc =
                declaration.findDeclaration<IrSimpleFunction> { it.name == Name.identifier("callMember") }
            require(
                callMemberFunc != null && checkCallMemberFunc(
                    context,
                    callMemberFunc
                )
            ) {
                """ |callMember should have exactly this signature:
                        |fun <T> callMember(thisRef: Any?, member: KCallable<T>, arguments: Map<String, Any?>): T
                        |""".trimMargin()
            }
            callMemberFunc
        }
        val getValueFunc by lazy(LazyThreadSafetyMode.NONE) {
            val f = declaration.findDeclaration<IrSimpleFunction> {
                it.name == Name.identifier("getValue") && it.isOperator
            }
            requireNotNull(f) { "operator fun getValue() not found in class ${declaration.fqNameWhenAvailable}" }
        }
        val setValueFunc by lazy(LazyThreadSafetyMode.NONE) {
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
                                type = context.irBuiltIns.unitType
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
                    val argMap = irTemporary(irCall(
                        linkedMapOfFunction,
                        mapStringAnyType
                    ).apply {
                        typeArguments(context.irBuiltIns.stringType, context.irBuiltIns.anyNType)
                    }, "argMap"
                    )
                    for (arg in newDecl.valueParameters) {
                        +irMemberCall(
                            mapPutFunction,
                            irGet(argMap),
                            irString(arg.name.asString()),
                            irGet(arg),
                            type = context.irBuiltIns.anyNType
                        )
                    }

                    +irReturn(
                        irAs(
                            irCall(callMemberFunc).apply {
                                dispatchReceiver = irThis()
                                valueArguments(
                                    irGet(newDecl.extensionReceiverParameter ?: newDecl.dispatchReceiverParameter!!),
                                    irGet(ref),
                                    irGet(argMap)
                                )
                                typeArguments(decl.returnType)
                            },
                            decl.returnType
                        )
                    )

                }
            }
        }
    }

    fun checkCallMemberFunc(context: IrPluginContext, func: IrSimpleFunction): Boolean {
        val typeParams = func.typeParameters
        val valueParams = func.valueParameters

        func.name == Name.identifier("callMember") || return false
        typeParams.size == 1 || return false
        val (typeParam) = typeParams
        valueParams.size == 3 || return false
        val (thisRef, cref, args) = valueParams
        thisRef.type == context.irBuiltIns.anyNType || return false
        context.irBuiltIns.kFunctionClass.typeWith(typeParam.defaultType).isSubtypeOf(
            cref.type,
            context.irBuiltIns
        ) || return false
        mapStringAnyType.isSubtypeOf(
            args.type,
            context.irBuiltIns
        ) || return false

        return true
    }
}
