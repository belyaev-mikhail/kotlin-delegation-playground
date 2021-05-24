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
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.backend.js.utils.getSingleConstStringArgument
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class PluginSampleTransformer(
    private val file: IrFile,
    private val fileSource: String,
    private val context: IrPluginContext,
    private val messageCollector: MessageCollector,
    private val functions: Set<FqName>
) : IrElementTransformerVoidWithContext() {

    private val any = context.irBuiltIns.anyClass.owner
    private val equals = any.functions.single { it.name == Name.identifier("equals") }
    private val hashCode = any.functions.single { it.name == Name.identifier("hashCode") }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    override fun visitClassNew(declaration: IrClass): IrStatement {
        val annotation = declaration.getAnnotation(FqName("DataLike"))
            ?: return super.visitClassNew(declaration)

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
        this.origin = IrDeclarationOrigin.GENERATED_DATA_CLASS_MEMBER
    }

    result.parent = this
    result.dispatchReceiverParameter = thisReceiver?.copyTo(result)
    result.valueParameters =
        existing.valueParameters.map { it.copyTo(result, IrDeclarationOrigin.GENERATED_DATA_CLASS_MEMBER) }

    result.overriddenSymbols = existing.overriddenSymbols

    declarations.remove(existing)
    return result
}
