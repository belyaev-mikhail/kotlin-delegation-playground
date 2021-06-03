package ru.spbstu

fun <T> generated(): T = TODO()

sealed class Expr
@DataLike
class Var(val name: String): Expr(), Comparable<Var> {
    override fun compareTo(other: Var): Int = generated()
}
@DataLike
class Val<T: Comparable<T>>(val value: T): Expr(), Comparable<Val<T>> {
    override fun compareTo(other: Val<T>): Int = generated()
}
@DataLike
class Const(val value: Int): Expr(), Comparable<Const> {
    override fun compareTo(other: Const): Int = generated()
}
@DataLike
class Binary(val lhv: Expr, val rhv: Expr, val op: String): Expr(), Comparable<Binary> {
    override fun compareTo(other: Binary): Int = generated()
}
@DataLike
class Multiple(vararg val elements: Expr): Expr()

