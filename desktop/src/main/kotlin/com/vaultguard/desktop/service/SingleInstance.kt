package com.vaultguard.desktop.service

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * One tray service per user.
 *
 * Two copies would both try to own the bridge port and the handshake file, and the
 * browser extension would reach whichever won - possibly the locked one. Before this a
 * second launch, from the Start Menu or the Run key racing a manual start, simply added a
 * second padlock to the tray.
 *
 * A `FileLock` rather than a pid file: the operating system releases it when the process
 * dies, however it dies, so a crash leaves nothing stale to clean up or to misread. Held
 * for the life of the process by the reference kept here; the file itself is empty and
 * stays behind, which is harmless.
 */
object SingleInstance {

    val defaultLockFile: File get() = File(Setup.stateDirectory, "service.lock")

    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** @return false if another service already holds the lock. */
    fun acquire(lockFile: File = defaultLockFile): Boolean {
        lockFile.parentFile?.mkdirs()
        return try {
            val opened = RandomAccessFile(lockFile, "rw").channel
            val held = try {
                opened.tryLock()
            } catch (e: java.nio.channels.OverlappingFileLockException) {
                // This JVM already holds it - the same answer as another process holding it.
                null
            }
            if (held == null) {
                opened.close()
                false
            } else {
                channel = opened
                lock = held
                true
            }
        } catch (e: Exception) {
            // Could not even open the file: treat as free rather than refusing to start
            // over a permissions oddity. The bridge port collision will still be reported.
            true
        }
    }

    /** For tests; the service holds the lock until the process ends. */
    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }
}
