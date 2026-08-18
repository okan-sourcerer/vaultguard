package com.vaultguard.app.di

import javax.inject.Qualifier

/**
 * Argon2id at 64 MiB blocks for hundreds of milliseconds. Injecting the dispatcher rather
 * than hard-coding it keeps that off the main thread (finding #17) and lets tests assert
 * it stays off.
 *
 * The qualifier lives in `:core` because `MasterPasswordManager` carries it. It is a plain
 * JSR-330 annotation — Hilt binds it on Android via `DispatcherModule`, and a desktop
 * client can pass a dispatcher directly and ignore it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CryptoDispatcher
