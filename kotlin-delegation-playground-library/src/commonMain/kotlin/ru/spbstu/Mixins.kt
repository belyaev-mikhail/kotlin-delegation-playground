package ru.spbstu

import kotlin.reflect.KClass

interface Mixin<out Self> {
    val self: Self
}

inline fun <reified D, reified M: Mixin<D>> mixin(kclass: KClass<M>): D =
    /*
        not valid kotlin, but roughly equivalent to, given delegatee class
        object : D by this@delegatee, M() {
            override val self = this@delegatee
            // for each member of M overriding D
            override fun f() = super<M>.f()
        }
    * */
    throw IllegalStateException("mixin() should never be called, please configure your plugin correctly")
inline fun <reified M: Mixin<*>> mixin(): M = mixin<Any?, M>(M::class) as M
