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

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.FqName

val KEY_ANNOTATIONS = CompilerConfigurationKey<List<String>>("fully-qualified annotation names")

@AutoService(ComponentRegistrar::class)
class PluginSampleComponentRegistrar(
  private val annotationNames: Set<FqName>
) : ComponentRegistrar {
  @Suppress("unused") constructor() : this(emptySet()) // Used by service loader

  override fun registerProjectComponents(
    project: MockProject,
    configuration: CompilerConfiguration
  ) {
    val functions = configuration[KEY_ANNOTATIONS]?.map { FqName(it) } ?: annotationNames
    if (functions.isEmpty()) return

    val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    IrGenerationExtension.registerExtension(project, PluginSampleIrGenerationExtension(messageCollector, functions.toSet()))
  }
}


