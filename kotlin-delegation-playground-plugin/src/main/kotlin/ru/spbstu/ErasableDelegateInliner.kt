package ru.spbstu

import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name

class ErasableDelegateInliner(val context: IrPluginContext, val messageCollector: MessageCollector) {

    operator fun invoke(declaration: IrProperty) {
        val delegateClass = declaration.backingField!!.type.classOrFail
        val delegateField = delegateClass.owner.properties.single().backingField!!

        val origType = declaration.backingField?.type!!

        val subsMap = delegateClass.owner.typeParameters.zip((origType as IrSimpleType).arguments) { k, v ->
            k.symbol to v.typeOrNull!!
        }.toMap()

        declaration.backingField?.type = delegateField.type.substitute(subsMap)

        declaration.backingField?.initializer =
            delegateField.initializer
                ?.deepCopyWithVariables()
                ?.patchDeclarationParents(declaration.parent)

        val getValueImpl = delegateClass.getSimpleFunction("getValue-impl")?.owner!!

        val originalCall =
            declaration.getter!!.findFirstChild<IrCall> { it.symbol.owner.name == Name.identifier("getValue") }!!

        declaration.getter?.buildExpressionBody(context) {
            val getter = parent.containingFunction()
            irCall(getValueImpl.symbol, declaration.type).apply {
                addTypeArguments(0, typeArgument = originalCall.allTypeArguments().toTypedArray())
                addTypeArguments(0, typeArgument = origType.arguments.map { it.typeOrNull }.toTypedArray())

                copyValueArgumentsFrom(originalCall, getValueImpl)
                dispatchReceiver = null

                val getterArgType = context.irBuiltIns.function(0).typeWith(declaration.type)
                val setterArgType = context.irBuiltIns.function(1).typeWith(declaration.type, context.irBuiltIns.unitType)

                val getterLambda = irLambda(getterArgType) {
                    buildExpressionBody(context) {
                        irGetField(irThis(getter), declaration.backingField!!)
                    }
                }

                val setterLambda = irLambda(setterArgType) {
                    addValueParameter {
                        name = Name.identifier("value")
                        type = delegateField.type
                    }
                    buildBlockBody(context) {
                        +irSetField(irThis(getter), declaration.backingField!!, irArg(0))
                    }
                }
                addValueArguments(originalCall.valueArgumentsCount, getterLambda, setterLambda)
            }
        }

        messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, declaration.dumpKotlinLike())
    }
}
