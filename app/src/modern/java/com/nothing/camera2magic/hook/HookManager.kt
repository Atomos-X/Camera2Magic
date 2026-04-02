package com.nothing.camera2magic.hook

import com.nothing.camera2magic.utils.Dog

interface HookManager {
    val hookedClasses: MutableSet<Class<*>>

    fun Class<*>.safeHook(block: Class<*>.() -> Unit) {
        if (hookedClasses.add(this)) {
            runCatching { block() }.onFailure {
                Dog.e("[HookManager]", "Failed to hook dynamic class: ${this.name}", it, true)
            }
        }
    }

    fun ClassLoader.safeHook(className: String, block: Class<*>.() -> Unit) {
        runCatching { loadClass(className) }
            .onSuccess { it.safeHook { block() } }
            .onFailure { Dog.w("[HookManager]", "Class[$className] Not Founded !", true) }
    }
}