package com.vaultguard.desktop.service

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The tray's Settings window, minus the Swing: applies what the CLI only prints.
 *
 * `--install-service` and `--install-bridge` generate files and then print the one
 * registry (or `cp`, or `launchctl`) command that makes them take effect, because a
 * persistent change to what happens at login or to what a browser will run is the user's
 * to make knowingly. A checkbox the user ticks in a window titled Settings satisfies that
 * at least as well as a pasted `reg add` — better, since the box also shows whether it is
 * currently on. So this runs exactly the commands those reports carry, through the same
 * shell the user would have typed them into, and nothing else: one code path decides what
 * the command is, and the CLI and the window cannot disagree about it.
 */
object Setup {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    data class Outcome(val lines: List<String>, val problems: List<String>) {
        val succeeded: Boolean get() = problems.isEmpty()
    }

    // -- Start at login ---------------------------------------------------------------------

    fun isAtLogin(): Boolean = when {
        isWindows -> run("reg query \"${ServiceInstall.RUN_KEY}\" /v ${ServiceInstall.RUN_VALUE}").succeeded
        isMac -> ServiceInstall.launchAgentFile.isFile
        else -> ServiceInstall.autostartEntryFile.isFile
    }

    fun setAtLogin(enabled: Boolean): Outcome {
        val report = if (enabled) ServiceInstall.install(atLogin = true) else ServiceInstall.uninstall()
        if (!report.succeeded) return Outcome(report.lines, report.problems)
        return apply(report.lines, report.commands)
    }

    // -- Browser extension ------------------------------------------------------------------

    data class BridgeStatus(val firefox: Boolean, val chromeExtensionId: String?)

    fun bridgeStatus(): BridgeStatus = BridgeInstall.status()

    /** Registers Firefox always, Chrome too when an id is given. */
    fun registerBridge(chromeExtensionId: String?): Outcome {
        val report = BridgeInstall.install(chromeExtensionId?.trim()?.takeIf { it.isNotEmpty() }, BridgeInstall.locateLauncher())
        if (!report.succeeded) return Outcome(report.written, report.problems)
        return apply(report.written, report.registryCommands)
    }

    // -- Running the printed commands ------------------------------------------------------

    private fun apply(lines: List<String>, commands: List<String>): Outcome {
        val problems = mutableListOf<String>()
        val done = lines.toMutableList()
        for (command in commands) {
            val result = run(command.trim())
            if (result.succeeded) done += "Applied: ${command.trim()}" else problems += result.output.ifBlank { "Failed: ${command.trim()}" }
        }
        return Outcome(done, problems)
    }

    private data class Run(val succeeded: Boolean, val output: String)

    /**
     * Through `cmd /c` or `sh -c` rather than tokenised by hand: the printed line is what
     * the user was told to type, and the shell's own quoting rules are the ones it was
     * written for. Not user input - every command here is composed by this program.
     */
    private fun run(command: String): Run = try {
        val process = ProcessBuilder(
            if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Run(false, "Timed out: $command")
        } else {
            Run(process.exitValue() == 0, output.trim())
        }
    } catch (e: Exception) {
        Run(false, e.message ?: e.javaClass.simpleName)
    }

    /** Where the desktop keeps its state; shown in Settings so a person can find it. */
    val stateDirectory: File get() = File(System.getProperty("user.home"), ".vaultguard")
}
