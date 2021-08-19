package ru.spbstu

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KCallable
import kotlin.reflect.KProperty

interface ProxyDelegate {
    operator fun <T> getValue(thisRef: Any?, property: KProperty<*>): T
    operator fun <T> setValue(thisRef: Any?, property: KProperty<*>, value: T)
    fun <T> callMember(thisRef: Any?, member: KCallable<T>, arguments: Map<String, Any?>): T
}
