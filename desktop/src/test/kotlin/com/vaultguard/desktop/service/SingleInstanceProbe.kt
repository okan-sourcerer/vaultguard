package com.vaultguard.desktop.service

import java.io.File

/** Run in a second JVM by [SingleInstanceTest]: prints whether it could take the lock. */
object SingleInstanceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println(if (SingleInstance.acquire(File(args[0]))) "acquired" else "held")
    }
}
