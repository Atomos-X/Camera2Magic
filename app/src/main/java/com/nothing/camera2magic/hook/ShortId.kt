package com.nothing.camera2magic.hook

val Any?.shortId : String
    get() = if (this == null) "null"
    else "${this::class.java.simpleName}@0x${Integer.toHexString(System.identityHashCode(this))}"