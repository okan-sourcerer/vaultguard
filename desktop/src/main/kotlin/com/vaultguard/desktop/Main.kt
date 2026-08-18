package com.vaultguard.desktop

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.PasswordGeneratorConfig
import com.vaultguard.app.domain.usecase.GeneratePasswordUseCase
import com.vaultguard.app.domain.usecase.backup.VaultBackupFormat
import com.vaultguard.app.util.PasswordStrengthEvaluator
import com.vaultguard.desktop.cloud.DesktopConfig
import com.vaultguard.desktop.cloud.SavedSession
import com.vaultguard.desktop.service.BridgeInstall
import com.vaultguard.desktop.service.NativeHost
import com.vaultguard.desktop.service.runTrayService
import java.io.File
import java.util.UUID

private val generate = GeneratePasswordUseCase()
private val strength = PasswordStrengthEvaluator()

private val USAGE = """
vaultguard <backup-file>    open a format-v2 backup file
vaultguard --cloud          open the Firestore vault your phone publishes
vaultguard --cloud-setup    write a configuration template for --cloud
vaultguard --cloud-signout  forget the saved sign-in
vaultguard --service        run in the system tray
vaultguard --install-bridge [chrome-extension-id]
                            register the browser native-messaging host

File mode opens a backup, or starts a new vault if the file does not exist yet.
Generate passwords, add entries, and write the file back out. Nothing reaches the
file until you type `save`.

Cloud mode signs in with Google once and remembers it, so later runs need only
your master password. It browses, adds, edits and soft-deletes. Every write is
checked against the version it fetched, so if your phone changed an entry since,
the write is refused rather than overwriting it.

Service mode puts a tray icon in the notification area: unlock, refresh, lock,
sign out, quit. It holds the vault open so other things can use it, and locks
itself after 15 minutes unused.

Neither mode keeps a copy of your password. Both derive per operation and let
KeyDerivation zero the array. The remembered sign-in is sealed under your master
key, so the file is inert without it.
""".trim()

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        println(USAGE)
        return
    }

    when (args[0]) {
        // Started by the browser, never by a person. Nothing may be printed to stdout on
        // this path: the stream is a framed protocol and a stray line corrupts it.
        "--native-host" -> NativeHost.run()
        "--install-bridge" -> installBridge(args.getOrNull(1))
        "--cloud" -> runCloudSession()
        "--cloud-setup" -> cloudSetup()
        "--cloud-signout" -> cloudSignOut()
        "--service" -> runTrayService()
        else -> {
            if (args.size != 1) {
                println(USAGE)
                return
            }
            val file = File(args[0])
            val vault = openOrCreate(file) ?: return
            Session(file, vault).run()
        }
    }
}

private fun cloudSetup() {
    val path = DesktopConfig.defaultPath
    if (DesktopConfig.writeTemplate(path)) {
        println("Wrote a configuration template to ${path.path}")
        println("Fill it in, then run `vaultguard --cloud`.")
    } else {
        println("${path.path} already exists — leaving it alone.")
    }
    println()
    println(DesktopConfig.template)
}

private fun installBridge(chromeExtensionId: String?) {
    val launcher = File(
        File(System.getProperty("user.dir")),
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            "desktop/build/install/vaultguard/bin/vaultguard.bat"
        } else {
            "desktop/build/install/vaultguard/bin/vaultguard"
        }
    )

    println("Registering the native messaging host.")
    println()
    BridgeInstall.install(chromeExtensionId, launcher).forEach { println(it) }
    println()
    println("The host manifest points at the launcher above. On Windows the browsers find")
    println("it through the registry, so run the `reg add` line(s) printed above yourself —")
    println("they change your browser configuration and are yours to make knowingly.")
    println()
    println("The launcher must pass --native-host through. If you moved it, re-run this.")
}

private fun cloudSignOut() {
    val path = SavedSession.defaultPath
    if (SavedSession.clear(path)) {
        println("Forgot the saved sign-in at ${path.path}.")
        println("The next `--cloud` will ask you to sign in with Google again.")
    } else {
        println("There was no saved sign-in at ${path.path}.")
    }
    println()
    println("This does not revoke anything at Google. To do that, remove this app's access")
    println("at https://myaccount.google.com/permissions")
}

private fun openOrCreate(file: File): BackupVault? {
    if (!file.exists()) {
        println("${file.path} does not exist - starting a new vault.")
        println("It is written only when you `save`, and only if it holds at least one entry.")
        return BackupVault()
    }

    val password = readPassword("Backup password for ${file.name}: ")
    if (password == null) {
        System.err.println("No password given.")
        return null
    }

    print("Deriving key (Argon2id, 64 MiB - this takes a moment)... ")
    System.out.flush()
    val vault = try {
        // read() consumes the array; there is no second derivation here, so no copy.
        BackupFile.read(file, password)
    } catch (e: WrongBackupPasswordException) {
        println()
        System.err.println(e.message)
        return null
    } catch (e: VaultBackupFormat.UnsupportedBackupException) {
        println()
        System.err.println(e.message)
        return null
    }
    println("opened. ${vault.size} ${entryWord(vault.size)}.")
    return vault
}

private fun entryWord(count: Int) = if (count == 1) "entry" else "entries"

private class Session(private val file: File, private val vault: BackupVault) {

    private var dirty = false

    fun run() {
        println("Type `help` for commands.")
        while (true) {
            print("\nvaultguard> ")
            System.out.flush()
            val line = readlnOrNull()?.trim() ?: return
            val parts = line.split(" ").filter { it.isNotBlank() }
            if (parts.isEmpty()) continue

            when (parts[0].lowercase()) {
                "help", "?" -> help()
                "list", "ls" -> list()
                "show" -> show(parts.getOrNull(1))
                "gen" -> gen(parts.getOrNull(1))
                "add" -> add()
                "save" -> save()
                "quit", "exit", "q" -> if (confirmQuit()) return
                else -> println("Unknown command: ${parts[0]}. Try `help`.")
            }
        }
    }

    private fun help() = println(
        """
        list            list entries
        show <n>        show one entry in full, including its password
        gen [length]    generate a password (default ${PasswordGeneratorConfig().length})
        add             add an entry, generating a password unless you supply one
        save            write the vault back to ${file.name}
        quit            exit
        """.trimIndent()
    )

    private fun list() {
        if (vault.size == 0) {
            println("No entries yet. `add` to create one.")
            return
        }
        vault.credentials.forEachIndexed { index, credential ->
            val where = credential.url.ifEmpty { credential.siteName }
            val name = credential.displayName.padEnd(24)
            val user = credential.username.padEnd(24)
            println("  [${index + 1}] $name $user $where")
        }
    }

    private fun show(argument: String?) {
        val credential = resolve(argument) ?: return
        println("  name      ${credential.displayName}")
        println("  username  ${credential.username}")
        println("  url       ${credential.url}")
        println("  password  ${credential.password}")
        if (credential.notes.isNotEmpty()) println("  notes     ${credential.notes}")
        describe(credential.password)
    }

    private fun gen(argument: String?) {
        val length = argument?.toIntOrNull() ?: PasswordGeneratorConfig().length
        if (length < 4 || length > 256) {
            println("Length must be between 4 and 256.")
            return
        }
        val password = generate(PasswordGeneratorConfig(length = length))
        println("  $password")
        describe(password)
    }

    private fun add() {
        val name = prompt("Site or app name") ?: return
        if (name.isBlank()) {
            println("A name is required.")
            return
        }
        val username = prompt("Username") ?: return
        val url = prompt("URL (optional)") ?: return
        val supplied = prompt("Password (blank to generate)") ?: return

        val password = supplied.ifBlank {
            generate(PasswordGeneratorConfig()).also { println("Generated: $it") }
        }
        describe(password)

        val now = System.currentTimeMillis()
        vault.add(
            Credential(
                id = UUID.randomUUID().toString(),
                siteName = name,
                url = url,
                username = username,
                password = password,
                createdAt = now,
                updatedAt = now,
                passwordChangedAt = now,
                contentChangedAt = now
            )
        )
        dirty = true
        println("Added. ${vault.size} ${entryWord(vault.size)} - unsaved.")
    }

    private fun save() {
        if (vault.size == 0) {
            println("Nothing to save: the vault is empty.")
            return
        }

        // Prompted rather than held from `open`, so the session keeps no copy of the
        // password in memory. Confirmed because a fresh key is derived here: a typo would
        // write a perfectly valid file that opens under a password nobody knows.
        val password = readPassword("Backup password to write under: ") ?: return
        val again = readPassword("Confirm: ")
        if (again == null) {
            password.fill('\u0000')
            return
        }
        if (!password.contentEquals(again)) {
            password.fill('\u0000')
            again.fill('\u0000')
            println("Passwords do not match. Nothing written.")
            return
        }
        again.fill('\u0000')

        print("Deriving key and writing... ")
        System.out.flush()
        try {
            BackupFile.write(file, vault, password)
        } catch (e: Exception) {
            println()
            System.err.println("Not written: ${e.message}")
            return
        }
        dirty = false
        println("saved ${vault.size} ${entryWord(vault.size)} to ${file.path}")
    }

    private fun confirmQuit(): Boolean {
        if (!dirty) return true
        print("You have unsaved changes. Quit anyway? [y/N] ")
        System.out.flush()
        return readlnOrNull()?.trim()?.lowercase() == "y"
    }

    private fun resolve(argument: String?): Credential? {
        val index = argument?.toIntOrNull()
        if (index == null || index !in 1..vault.size) {
            println("Give an entry number from 1 to ${vault.size}.")
            return null
        }
        return vault.credentials[index - 1]
    }

    private fun describe(password: String) {
        val result = strength(password)
        val note = if (result.isCommon) " - this is a known-common password" else ""
        println("  ${result.level} (${result.entropy.toInt()} bits)$note")
    }

    private fun prompt(label: String): String? {
        print("$label: ")
        System.out.flush()
        return readlnOrNull()
    }
}

/**
 * Reads without echo where the JVM has a real console. Under Gradle or an IDE it does not,
 * and the fallback echoes - said plainly rather than pretending otherwise.
 */
private fun readPassword(label: String): CharArray? {
    val console = System.console()
    if (console != null) return console.readPassword(label)

    print("$label(no console - input will be visible) ")
    System.out.flush()
    return readlnOrNull()?.toCharArray()
}
