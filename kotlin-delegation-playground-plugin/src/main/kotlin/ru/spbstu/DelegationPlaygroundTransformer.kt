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
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fields
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.reflect.KFunction

object DELEGATION_PLAYGROUND_PLUGIN_GENERATED_ORIGIN : IrDeclarationOriginImpl("DELEGATION_PLAYGROUND_PLUGIN_GENERATED", true)

fun <F> nameOf(f: F): Name
where F: KFunction<*>, F: () -> Any? { return Name.identifier(f.name) }
fun <F> nameOf(f: F, dis: Unit = Unit): Name
where F: KFunction<*>, F: (Nothing) -> Any? { return Name.identifier(f.name) }
fun <F> nameOf(f: F, dis: Unit = Unit, dis2: Unit = Unit): Name
where F: KFunction<*>, F: (Nothing, Nothing) -> Any? { return Name.identifier(f.name) }
fun <F> nameOf(f: F, dis: Unit = Unit, dis2: Unit = Unit, dis3: Unit = Unit): Name
where F: KFunction<*>, F: (Nothing, Nothing, Nothing) -> Any? { return Name.identifier(f.name) }

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
            }.associateBy (
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
                    ex.message ?: "Unknown error"
                )
                throw ex
            } catch (ex: IllegalStateException) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    ex.message ?: "Unknown error"
                )
                throw ex
            }

        return super.visitClassNew(declaration)
    }

}


