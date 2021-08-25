package ru.spbstu

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFieldAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.name.FqName

class LazyDelegateAdapter(val context: IrPluginContext) {
    val irBuiltins = context.irBuiltIns
    val irFactory = context.irFactory

    val lazyClass: IrClass by lazy {
        context.referenceClass<Lazy<*>>().owner
    }

    val lazyPropValue: IrProperty by lazy {
        context.findDeclaration(Lazy<*>::value)
    }

    val lazyFunction: IrSimpleFunctionSymbol by lazy {
        context.referenceFunctions(FqName("kotlin.lazy")).single {
            val params = it.owner.valueParameters
            params.size == 1 && params.first().type.isFunction()
        }
    }
    fun visitClass(
        context: IrPluginContext,
        declaration: IrClass, delegate: IrField, iface: IrClassSymbol
    ) {
        val ifaceType = declaration.superTypes.find { it.classOrNull == iface }!!

        val initCall = delegate.delegateInitializerCall!!
        val newField = declaration.addField {
            updateFrom(delegate)
            name = delegate.name
            type = lazyClass.typeWith(ifaceType)
        }.apply {
            val newField = this
            val arg = initCall.getValueArgument(0)!!
            val pType = initCall.symbol.owner.valueParameters.first().type
            when {
                pType.classOrNull == lazyClass.symbol ->
                    buildInitializer(context) { arg.patchDeclarationParents(newField) }
                pType.isFunctionTypeOrSubtype() -> {
                    buildInitializer(context) {
                        irCall(lazyFunction, type).apply {
                            valueArguments(arg.patchDeclarationParents(newField))
                            typeArguments(ifaceType)
                        }
                    }
                }
                else -> error("Unknown lazyDelegate() function invocation")
            }
        }
        val remapper: IrElementTransformerVoidWithContext = object: IrElementTransformerVoidWithContext() {
            override fun visitFieldAccess(expression: IrFieldAccessExpression): IrExpression {
                if (expression.symbol == delegate.symbol) {
                    return currentScope!!.buildAStatement(context) {
                        irMemberCall(lazyPropValue.getter!!, irGetField(irThis(), newField), type = delegate.type)
                    }
                }
                return super.visitFieldAccess(expression)
            }
        }

        declaration.transformChildren(remapper, null)
    }
}
