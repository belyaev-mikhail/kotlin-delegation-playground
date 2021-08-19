package ru.spbstu

import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.time.days
import kotlin.collections.mutableMapOf

interface Runnable {
    fun count(): Int
    fun assign(name: String, value: Double = 3.14): Int

    val size: Long
}

interface Skippable {
    fun skip() {}
}

class Foo: Runnable by proxyDelegate(), Skippable by (lazyDelegate { object : Skippable{} }) {
    operator fun <T> getValue(thisRef: Any?, property: KProperty<*>): T {
        return when(property.name) {
            "size" -> 108.toLong()
            else -> error("Unknown property $property")
        } as T
    }

//    operator fun <T> setValue(thisRef: Any?, property: KProperty<*>, value: T) {
//        TODO("Not yet implemented")
//    }

    inline fun <reified T> callMember(thisRef: Any?, member: KCallable<T>, arguments: Map<String, Any?>): T {
        return when(member.name) {
            "count" -> 43
            "assign" -> {
                val name by arguments
                val value by arguments
                name.toString().toIntOrNull() ?: (value as Double).toInt()
            }
            else -> error("Don't know how to handle function $member")
        } as T
    }

}

