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

package com.bnorm.power

import org.jetbrains.kotlin.name.FqName
import org.junit.Test
import kotlin.test.assertEquals

class CompilerTest {
  @Test
  fun simple() {
    assertEquals(2, executeExpr("2"))
    assertEquals(2, executeSource("""
      class Data(val x: Int)
      fun foo() = 59
      fun main(): Int = foo()
    """))
  }
}
