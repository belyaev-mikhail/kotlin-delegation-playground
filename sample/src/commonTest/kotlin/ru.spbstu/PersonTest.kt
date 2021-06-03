package ru.spbstu

import kotlin.test.Test
import kotlin.test.assertTrue

class PersonTest {
    @Test
    fun smokey() {
        assertTrue { Binary(Var("x"), Val(10), "+") == Binary(Var("x"), Val(10), "+") }
        assertTrue { Var("a") < Var("b") }
        assertTrue { Val(12) < Val(13) }

        val mm = Multiple(Var("x"), Var("y"), Var("z"))
        val mm1 = Multiple(Var("x"), Var("y"), Var("z"))
        //println("mm = $mm")
        //assertTrue { mm.hashCode() == mm1.hashCode() }
    }
}
