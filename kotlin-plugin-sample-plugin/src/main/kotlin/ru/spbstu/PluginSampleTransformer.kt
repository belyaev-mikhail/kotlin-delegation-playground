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

import org.jetbrains.kotlin.backend.common.DataClassMethodGenerator
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.asSimpleLambda
import org.jetbrains.kotlin.backend.common.ir.inline
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.backend.js.utils.asString
import org.jetbrains.kotlin.ir.backend.js.utils.isEqualsInheritedFromAny
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.interpreter.toIrConst
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.declarationRecursiveVisitor
import org.jetbrains.kotlin.util.OperatorNameConventions

class PluginSampleTransformer(
  private val file: IrFile,
  private val fileSource: String,
  private val context: IrPluginContext,
  private val messageCollector: MessageCollector,
  private val functions: Set<FqName>
) : IrElementTransformerVoidWithContext() {

  @OptIn(ObsoleteDescriptorBasedAPI::class)
  override fun visitClassNew(declaration: IrClass): IrStatement {
    val builder = DeclarationIrBuilder(context, currentScope!!.scope.scopeOwnerSymbol)

    val dmg = object: DataClassMembersGenerator(
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
        return object: HashCodeFunctionInfo {
          override val symbol: IrSimpleFunctionSymbol = symbol
          override fun commitSubstituted(irMemberAccessExpression: IrMemberAccessExpression<*>) {}
        }
      }

      override fun getProperty(parameter: ValueParameterDescriptor?, irValueParameter: IrValueParameter?): IrProperty? {
        return declaration.properties.single {
          it.name == irValueParameter?.name && it.getter?.returnType == irValueParameter.type }
      }

      override fun transform(typeParameterDescriptor: TypeParameterDescriptor): IrType =
        context.irBuiltIns.anyType
    }
    dmg.generateEqualsMethod(
      declaration.functions.find { it.isEqualsInheritedFromAny() }!!,
      declaration.properties.toList()
    )

    messageCollector.report(CompilerMessageSeverity.INFO,
      declaration.declarations.joinToString("\n") { it.dump() })

    return super.visitClassNew(declaration)
  }

  override fun visitCall(expression: IrCall): IrExpression {
    val func = expression.symbol.owner.kotlinFqName
    System.err.println("$expression")
    //if (func !in functions) return super.visitCall(expression)

    messageCollector.report(CompilerMessageSeverity.WARNING, "Hello, ${expression.symbol.owner.kotlinFqName.asString()}",)
    val builder = DeclarationIrBuilder(context, currentScope!!.scope.scopeOwnerSymbol)
    return builder.irInt(2)
  }

}
