package com.vaultguard.desktop.service

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The clipboard, behind an interface so the clearing rule can be tested without a desktop. */
interface ClipboardAccess {
    fun read(): String?
    fun write(value: String)
}

/** The real one. */
class SystemClipboard : ClipboardAccess {
    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun read(): String? = try {
        clipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (e: Exception) {
        // An empty clipboard, or one holding something that is not text, both land here.
        null
    }

    override fun write(value: String) {
        clipboard.setContents(StringSelection(value), null)
    }
}

/**
 * Copies a secret and takes it back after a while.
 *
 * ## The rule that matters
 *
 * Clearing only happens if the clipboard still holds what was put there. Between the copy
 * and the timeout the user may well have copied something else — a URL, a paragraph, an
 * account number — and a password manager that wipes it is a password manager people stop
 * using. Android learned this the hard way in this codebase (#31, #46).
 *
 * ## What this does not protect against
 *
 * Windows clipboard history (Win+V) keeps its own copy, and nothing reachable from AWT
 * excludes an entry from it. Anything already synced to another device by Cloud Clipboard is
 * likewise beyond reach. The honest claim is "removed from the clipboard", not "unrecoverable"
 * — turn clipboard history off if that distinction matters to you.
 */
class ClipboardGuard(
    private val clipboard: ClipboardAccess = SystemClipboard(),
    private val holdSeconds: Long = DEFAULT_HOLD_SECONDS
) {
    companion object {
        const val DEFAULT_HOLD_SECONDS = 30L
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vaultguard-clipboard").apply { isDaemon = true }
    }

    private var pending: ScheduledFuture<*>? = null

    /** What we last wrote, so a later clear can tell whether it is still ours to take back. */
    private var written: String? = null

    /**
     * @return the number of seconds it will be held for, so a caller can say so.
     */
    fun copy(secret: String): Long {
        // A second copy supersedes the first; otherwise the earlier timer would fire and
        // clear the newer secret ahead of its time.
        pending?.cancel(false)

        clipboard.write(secret)
        written = secret

        pending = scheduler.schedule({ clearIfUnchanged() }, holdSeconds, TimeUnit.SECONDS)
        return holdSeconds
    }

    /**
     * Removes what was copied, unless the clipboard has moved on.
     *
     * Public because "clear now" is worth offering, and because it is the whole of the
     * interesting behaviour.
     */
    fun clearIfUnchanged(): Boolean {
        val ours = written ?: return false
        if (clipboard.read() != ours) {
            // Someone copied something else. Not ours to clear.
            written = null
            return false
        }

        clipboard.write("")
        written = null
        return true
    }

    fun shutdown() {
        pending?.cancel(false)
        clearIfUnchanged()
        scheduler.shutdownNow()
    }
}
