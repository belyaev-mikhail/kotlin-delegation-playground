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

import org.jetbrains.kotlin.com.intellij.psi.compiled.ClassFileDecompilers
import org.jetbrains.org.objectweb.asm.ClassReader
import org.junit.Test
import ru.spbstu.executeExpr
import ru.spbstu.executeSource
import kotlin.test.assertEquals

class CompilerTest {
    @Test
    fun simple() {
        assertCompiles(
            """
            package ru.spbstu
            
            import kotlin.reflect.KProperty  
            annotation class ErasableDelegate

            @ErasableDelegate
            class LateInit<T> {
                var field: Any? = UNINITIALIZED

                inline operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
                    return if (field === UNINITIALIZED) throw IllegalStateException("Field not initialized yet")
                    else field as T
                }
                inline operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                    if (field !== UNINITIALIZED) throw IllegalStateException("Field already initialized")
                    else {
                        field = value
                    }
                }
                
                inline fun <T> getValueImpl(thisRef: Any?, property: KProperty<*>, getter: () -> Any?, setter: (Any?) -> Unit): T {
                    return if (getter() === UNINITIALIZED) throw IllegalStateException("Field not initialized yet")
                    else getter() as T
                }


                companion object {
                    @PublishedApi
                    internal val UNINITIALIZED = Any()
                    
                }
            }
            
            class TestClass {
                val x: Int by LateInit()
            }

        """.trimIndent()).apply {
            val genClasses = this.compiledClassAndResourceFiles.joinToString(" ") { it.absolutePath }
            println(genClasses)
            ProcessBuilder("javap -p -c ${genClasses}".split(" ")).inheritIO().start().waitFor()
        }


    }
}
