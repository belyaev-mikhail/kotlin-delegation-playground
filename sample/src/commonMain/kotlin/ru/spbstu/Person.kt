package ru.spbstu

import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.time.days

inline fun <T> pluginGenerated(): T = TODO()

sealed class Expr: Comparable<Expr>
@DataLike
class Var(val name: String): Expr(), Comparable<Expr> by pluginGenerated()
@DataLike
class Val<T: Comparable<T>>(val value: T): Expr(), Comparable<Expr> by pluginGenerated()
@DataLike
class Const(val value: Int): Expr(), Comparable<Expr> by pluginGenerated()
@DataLike
class Binary(val lhv: Expr, val rhv: Expr, val op: String): Expr(), Comparable<Expr> by pluginGenerated()

interface InterfaceProxy {
    operator fun <T> getValue(self: Any?, prop: KProperty<*>): T
    operator fun <T> setValue(self: Any?, prop: KProperty<*>, newValue: T)
    fun <R> callMember(self: Any?, function: KFunction<R>, vararg arguments: Any?): R
}

@DataLike
class Multiple(vararg val elements: Expr): Expr(), Comparable<Expr> by pluginGenerated() {
    inline fun <reified T> toStuff(body: (T) -> Unit) {

        body(this as T)
        println("Hello")
    }

    fun doStuff2() {
        toStuff { s: Multiple ->
            println(s.elements)
        }
    }
}

@DataLike
class Standalone(
    val firstName: String = "",
    val lastName: String = "",
    val number: Int
): Comparable<Standalone> by pluginGenerated()
