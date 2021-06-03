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

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.path
import org.jetbrains.kotlin.name.FqName
import java.io.File

class PluginSampleIrGenerationExtension(
  private val messageCollector: MessageCollector,
  private val annotationNames: Set<FqName>
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    for (file in moduleFragment.files) {
      val fileSource = File(file.path).readText()
        .replace("\r\n", "\n") // https://youtrack.jetbrains.com/issue/KT-41888
      PluginSampleTransformer(file, fileSource, pluginContext, messageCollector, annotationNames)
        .visitFile(file)
    }
  }
}
