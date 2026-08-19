package com.vaultguard.desktop.service

import java.util.concurrent.TimeUnit

/** An open window, as something to search the vault for. */
data class OpenWindow(val process: String, val title: String) {

    /**
     * What to type into the search box for this window.
     *
     * The process name, without its extension. A window title is whatever the application
     * felt like — "Inbox (14) - okan@example.com - Mozilla Thunderbird" — and searching for
     * the whole of it matches nothing. The process name is short, stable across documents,
     * and usually what a credential is named after.
     */
    val searchTerm: String get() = process.removeSuffix(".exe")

    override fun toString(): String =
        if (title.isBlank()) searchTerm else "$searchTerm  -  ${title.take(60)}"
}

/**
 * Lists the windows currently open, so one can be picked to filter the vault by.
 *
 * The user chooses; nothing watches. An earlier sketch of this had the service notice
 * application launches and offer credentials unprompted, which means a resident process
 * keeping a record of what you run. Asking only when asked is both simpler and less to
 * explain.
 *
 * Windows-only for now, through PowerShell rather than a native binding: this runs when a
 * button is pressed, not in a loop, so the cost of spawning a shell does not matter and it
 * saves a dependency.
 */
object WindowList {

    private const val SCRIPT =
        "Get-Process | Where-Object { \$_.MainWindowTitle -ne '' } | " +
            "ForEach-Object { \$_.ProcessName + '|' + \$_.MainWindowTitle }"

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val isSupported: Boolean get() = isWindows

    /**
     * Parses the script's output.
     *
     * Separated so the awkward parts have a test: a title may contain the separator, so the
     * split is bounded to the first one, and a process with an empty title is dropped
     * because it has nothing to show.
     */
    fun parse(output: String): List<OpenWindow> =
        output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null

                val separator = trimmed.indexOf('|')
                if (separator <= 0) return@mapNotNull null

                val process = trimmed.substring(0, separator).trim()
                // A title may well contain a pipe. Everything after the first is the title.
                val title = trimmed.substring(separator + 1).trim()
                if (process.isEmpty()) null else OpenWindow(process, title)
            }
            .distinctBy { it.process.lowercase() to it.title }
            .sortedBy { it.searchTerm.lowercase() }
            .toList()

    /** @return the open windows, or an empty list if they cannot be listed. */
    fun list(): List<OpenWindow> {
        if (!isWindows) return emptyList()

        return try {
            val process = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", SCRIPT
            ).redirectErrorStream(false).start()

            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            parse(output)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
