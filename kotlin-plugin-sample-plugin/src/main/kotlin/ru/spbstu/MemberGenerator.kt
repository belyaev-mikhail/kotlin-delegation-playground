/*
 * Copyright (C) 2021 Mikhail Belyaev
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

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.DataClassMembersGenerator
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.properties

@OptIn(ObsoleteDescriptorBasedAPI::class)
class MemberGenerator(
    context: IrPluginContext,
    val declaration: IrClass
) : DataClassMembersGenerator(
    context,
    context.symbolTable as SymbolTable,
    declaration,
    declaration.origin
) {
    override fun declareSimpleFunction(
        startOffset: Int,
        endOffset: Int,
        functionDescriptor: FunctionDescriptor
    ): IrFunction {
        TODO()
    }

    override fun generateSyntheticFunctionParameterDeclarations(irFunction: IrFunction) {}

    private fun getHashCodeFunction(klass: IrClass): IrSimpleFunctionSymbol =
        klass.functions.singleOrNull {
            it.name.asString() == "hashCode" && it.valueParameters.isEmpty() && it.extensionReceiverParameter == null
        }?.symbol
            ?: context.irBuiltIns.anyClass.functions.single { it.owner.name.asString() == "hashCode" }

    private val IrTypeParameter.erasedUpperBound: IrClass
        get() {
            // Pick the (necessarily unique) non-interface upper bound if it exists
            for (type in superTypes) {
                val irClass = type.classOrNull?.owner ?: continue
                if (!irClass.isInterface && !irClass.isAnnotationClass) return irClass
            }

            // Otherwise, choose either the first IrClass supertype or recurse.
            // In the first case, all supertypes are interface types and the choice was arbitrary.
            // In the second case, there is only a single supertype.
            return when (val firstSuper = superTypes.first().classifierOrNull?.owner) {
                is IrClass -> firstSuper
                is IrTypeParameter -> firstSuper.erasedUpperBound
                else -> error("unknown supertype kind $firstSuper")
            }
        }


    override fun getHashCodeFunctionInfo(type: IrType): HashCodeFunctionInfo {
        val classifier = type.classifierOrNull
        val symbol = when {
            classifier.isArrayOrPrimitiveArray -> context.irBuiltIns.dataClassArrayMemberHashCodeSymbol
            classifier is IrClassSymbol -> getHashCodeFunction(classifier.owner)
            classifier is IrTypeParameterSymbol -> getHashCodeFunction(classifier.owner.erasedUpperBound)
            else -> error("Unknown classifier kind $classifier")
        }
        return object : HashCodeFunctionInfo {
            override val symbol: IrSimpleFunctionSymbol = symbol
            override fun commitSubstituted(irMemberAccessExpression: IrMemberAccessExpression<*>) {}
        }
    }

    override fun getProperty(
        parameter: ValueParameterDescriptor?,
        irValueParameter: IrValueParameter?
    ): org.jetbrains.kotlin.ir.declarations.IrProperty {
        return declaration.properties.single {
            it.name == irValueParameter?.name && it.getter?.returnType == irValueParameter.type
        }
    }

    override fun transform(typeParameterDescriptor: TypeParameterDescriptor): IrType =
        context.irBuiltIns.anyType
}
