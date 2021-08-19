package ru.spbstu

inline fun <reified T> pluginGenerated(): T =
    throw IllegalStateException("pluginGenerated() should never be called, please configure your plugin correctly")

inline fun <reified T, U: T> lazyDelegate(lazy: Lazy<U>): T =
    throw IllegalStateException("lazyDelegate() should never be called, please configure your plugin correctly")

inline fun <reified T, U: T> lazyDelegate(noinline body: () -> U): T =
    throw IllegalStateException("lazyDelegate() should never be called, please configure your plugin correctly")

inline fun <reified T> proxyDelegate(): T =
    throw IllegalStateException("proxyDelegate() should never be called, please configure your plugin correctly")
