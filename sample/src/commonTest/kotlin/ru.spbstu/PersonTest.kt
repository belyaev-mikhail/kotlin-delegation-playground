package ru.spbstu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonTest {

    @Test
    fun smokey() {
        Foo().hashCode() // should not throw
        assertEquals(43, Foo().count())
        assertEquals(50, Foo().assign("50"))
        assertEquals(3, Foo().assign(""))
        assertEquals(108, Foo().size)
    }
}
