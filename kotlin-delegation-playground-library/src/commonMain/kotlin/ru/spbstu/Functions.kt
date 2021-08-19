package ru.spbstu

inline fun <reified T> pluginGenerated(): T =
    throw IllegalStateException("pluginGenerated() should never be called, please configure your plugin correctly")

inline fun <reified T> lazyDelegate(lazy: Lazy<T>): T =
    throw IllegalStateException("lazyDelegate() should never be called, please configure your plugin correctly")

inline fun <reified T> lazyDelegate(noinline body: () -> T): T =
    throw IllegalStateException("lazyDelegate() should never be called, please configure your plugin correctly")

inline fun <reified T> proxyDelegate(): T =
    throw IllegalStateException("proxyDelegate() should never be called, please configure your plugin correctly")
