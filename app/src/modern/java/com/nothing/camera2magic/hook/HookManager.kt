package com.nothing.camera2magic.hook

import com.nothing.camera2magic.utils.Dog

interface HookManager {
    val hookedClasses: MutableSet<Class<*>>
    fun Class<*>.runOnce(block: Class<*>.() -> Unit) {
        if (hookedClasses.add(this)) {
            runCatching { block() }.onFailure {
                Dog.e("[HookManager]", "Failed to hook ${this.name}",
                    it, true)
            }
        }
    }

    fun ClassLoader.safeHook(className: String, block: Class<*>.() -> Unit) {
        runCatching { loadClass(className) }
            .onSuccess { it.runOnce { block() } }
        //.onFailure { Dog.e("[HookManager]", "Class Not Founded !", null, true) }
    }

    fun Class<*>.safeHook(block: Class<*>.() -> Unit) {
        if (hookedClasses.add(this)) {
            runCatching { block() }.onFailure {
                Dog.e("[HookManager]", "Failed to hook dynamic class: ${this.name}", it, true)
            }
        }
    }
}