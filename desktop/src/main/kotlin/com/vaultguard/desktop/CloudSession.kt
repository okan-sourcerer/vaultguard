package com.vaultguard.desktop

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.PasswordGeneratorConfig
import com.vaultguard.app.domain.repository.VaultSnapshot
import com.vaultguard.app.domain.usecase.GeneratePasswordUseCase
import com.vaultguard.app.util.PasswordStrengthEvaluator
import com.vaultguard.desktop.cloud.CloudVault
import com.vaultguard.desktop.cloud.DesktopConfig
import com.vaultguard.desktop.cloud.FirebaseSignIn
import com.vaultguard.desktop.cloud.FirestoreClient
import com.vaultguard.desktop.cloud.GoogleOAuth
import com.vaultguard.desktop.cloud.RemoteVaultCodec
import java.util.UUID

private val evaluator = PasswordStrengthEvaluator()
private val generator = GeneratePasswordUseCase()

/**
 * Cloud mode: sign in, fetch the vault, unlock it, browse it, and add to it.
 *
 * Adding is the only write, and it can only ever create. Nothing here edits or deletes, so
 * the two-writer conflict rules are never reached — see `FirestoreClient`. Nothing is
 * written to disk at any point.
 */
fun runCloudSession() {
    val config = try {
        DesktopConfig.load()
    } catch (e: DesktopConfig.Companion.MissingConfigException) {
        System.err.println(e.message)
        return
    }

    val identity = try {
        GoogleOAuth(config).signIn { println(it) }
    } catch (e: Exception) {
        System.err.println("Sign-in failed: ${e.message}")
        return
    }

    val session = try {
        FirebaseSignIn(config).exchange(identity)
    } catch (e: Exception) {
        System.err.println("${e.message}")
        return
    }
    println("Signed in as ${session.email ?: session.uid}.")

    val client = FirestoreClient(config, session)

    val document = try {
        client.vaultDocument()
    } catch (e: Exception) {
        System.err.println("${e.message}")
        return
    }

    if (document == null) {
        // Not an error to fix by writing something. Creating a vault from here would be
        // exactly the "uploaded a vault the owner never asked for" behaviour that finding
        // #15 was about, and this client has no vault of its own to upload anyway.
        println("This account has no cloud vault.")
        println("Turn on sync in the app's Settings first; the phone publishes the vault.")
        return
    }

    val remoteConfig = RemoteVaultCodec.readVaultConfig(document)
    if (remoteConfig == null) {
        System.err.println("The cloud vault document is incomplete — it carries no usable configuration.")
        return
    }

    val password = readMasterPassword("Master password: ")
    if (password == null) {
        System.err.println("No password given.")
        return
    }

    print("Deriving key (Argon2id, 64 MiB)... ")
    System.out.flush()

    val vault = CloudVault()
    val vaultKey = try {
        vault.unlock(remoteConfig, password)
    } catch (e: Exception) {
        println()
        System.err.println("${e.message}")
        return
    }
    println("unlocked.")

    val browser = CloudBrowser(vault, vaultKey, client)
    if (!browser.refresh()) return
    browser.run()
}

/**
 * Says what could not be read, before anything else.
 *
 * An unreadable row is the one thing a password manager must never present as absence.
 * Four separate data-loss bugs in this codebase all reached the user as "you have no
 * passwords" because a decryption failure became a `null` (#40).
 */
private fun report(snapshot: VaultSnapshot<Credential>) {
    if (snapshot.hasUndecryptable) {
        System.err.println()
        System.err.println(
            "WARNING: ${snapshot.undecryptableCount} of " +
                "${snapshot.items.size + snapshot.undecryptableCount} entries could not be " +
                "decrypted and are NOT listed below:"
        )
        snapshot.undecryptableIds.forEach { System.err.println("  $it") }
        System.err.println("This vault is incomplete as shown. Check the phone before trusting it.")
        System.err.println()
    }

    if (snapshot.isGenuinelyEmpty) {
        println("The vault opened and is genuinely empty.")
    }
}

private class CloudBrowser(
    private val vault: CloudVault,
    private val vaultKey: javax.crypto.SecretKey,
    private val client: FirestoreClient
) {
    /**
     * What the vault looked like when it was last fetched.
     *
     * A snapshot, and named one for a reason: it does not track the cloud. An entry deleted
     * on the phone stays listed here until [refresh] re-reads, and a password manager
     * showing an entry that no longer exists is showing something untrue. `refresh` is the
     * answer rather than polling, because every fetch is a decryption pass over the whole
     * vault and the CLI is a foreground tool the user is already driving.
     */
    private var snapshot: VaultSnapshot<Credential> = VaultSnapshot(emptyList())

    private var hasFetched = false

    /**
     * Re-reads the vault from Firestore and decrypts it.
     *
     * @return false if the fetch failed, which at startup means there is nothing to browse.
     */
    fun refresh(): Boolean {
        print("Fetching credentials... ")
        System.out.flush()

        val rows = try {
            client.credentialDocuments().mapNotNull { RemoteVaultCodec.readCredentialRow(it) }
        } catch (e: Exception) {
            println()
            System.err.println("${e.message}")
            return false
        }

        val previous = snapshot.items.map { it.id }.toSet()
        snapshot = vault.decrypt(rows, vaultKey)
        println("${rows.size} ${if (rows.size == 1) "row" else "rows"}.")

        // Only from the second fetch on: at startup there is no "before" to compare
        // against, and an empty vault gaining entries is still a real delta worth showing.
        if (hasFetched) {
            val current = snapshot.items.map { it.id }.toSet()
            val gone = previous.count { it !in current }
            val fresh = current.count { it !in previous }
            if (gone > 0 || fresh > 0) println("$fresh new, $gone no longer here.")
        }
        hasFetched = true

        report(snapshot)
        return true
    }

    fun run() {
        println("Type `help` for commands. `add` writes; nothing else does.")
        while (true) {
            print("\nvaultguard(cloud)> ")
            System.out.flush()
            val line = readlnOrNull()?.trim() ?: return
            val parts = line.split(" ").filter { it.isNotBlank() }
            if (parts.isEmpty()) continue

            when (parts[0].lowercase()) {
                "help", "?" -> help()
                "list", "ls" -> list()
                "show" -> show(parts.getOrNull(1))
                "find" -> find(parts.drop(1).joinToString(" "))
                "refresh", "r" -> refresh()
                "gen" -> gen(parts.getOrNull(1))
                "add" -> add()
                "quit", "exit", "q" -> return
                else -> println("Unknown command: ${parts[0]}. Try `help`.")
            }
        }
    }

    private fun help() = println(
        """
        list            list entries
        show <n>        show one entry in full, including its password
        find <text>     entries whose name, username or URL contains <text>
        refresh         re-read the vault from the cloud
        gen [length]    generate a password without saving it
        add             create an entry and write it to the cloud vault
        quit            exit

        `add` is the only command that writes, and it can only create. Nothing here
        edits or deletes, in the cloud or anywhere else.

        The list is a snapshot from when it was last fetched, not a live view. If the
        phone has changed something since, `refresh`.
        """.trimIndent()
    )

    private fun list() {
        if (snapshot.items.isEmpty()) {
            println("Nothing to list.")
            return
        }
        snapshot.items.forEachIndexed { index, credential -> println(line(index, credential)) }
    }

    private fun find(query: String) {
        if (query.isBlank()) {
            println("Give something to search for.")
            return
        }
        val needle = query.lowercase()
        val hits = snapshot.items.withIndex().filter { (_, credential) ->
            listOf(credential.displayName, credential.username, credential.url, credential.siteName)
                .any { it.lowercase().contains(needle) }
        }
        if (hits.isEmpty()) {
            println("No match. (Searching only what decrypted successfully.)")
            return
        }
        hits.forEach { (index, credential) -> println(line(index, credential)) }
    }

    private fun gen(argument: String?) {
        val length = argument?.toIntOrNull() ?: PasswordGeneratorConfig().length
        if (length < 4 || length > 256) {
            println("Length must be between 4 and 256.")
            return
        }
        val password = generator(PasswordGeneratorConfig(length = length))
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
            generator(PasswordGeneratorConfig()).also { println("Generated: $it") }
        }
        describe(password)

        // The one place this client changes anything that outlives the process. Asked
        // rather than assumed: the vault on the other end is the real one.
        print("Write this to the cloud vault? [y/N] ")
        System.out.flush()
        if (readlnOrNull()?.trim()?.lowercase() != "y") {
            println("Nothing written.")
            return
        }

        val now = System.currentTimeMillis()
        val credential = Credential(
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

        print("Writing... ")
        System.out.flush()
        try {
            client.createCredential(vault.encrypt(credential, vaultKey))
        } catch (e: Exception) {
            println()
            System.err.println("Not written: ${e.message}")
            return
        }

        // Only after the write succeeds. Showing it in the list first would claim something
        // that had not happened. This is a local append rather than a refetch: the server
        // has it, and re-decrypting the whole vault to learn that would be wasteful.
        snapshot = snapshot.copy(
            items = (snapshot.items + credential).sortedBy { it.displayName.lowercase() }
        )
        println("saved.")
        println("The phone will pick it up on its next sync.")
    }

    private fun line(index: Int, credential: Credential): String {
        val name = credential.displayName.padEnd(24)
        val user = credential.username.padEnd(24)
        return "  [${index + 1}] $name $user ${credential.url.ifEmpty { credential.siteName }}"
    }

    private fun show(argument: String?) {
        val index = argument?.toIntOrNull()
        if (index == null || index !in 1..snapshot.items.size) {
            println("Give an entry number from 1 to ${snapshot.items.size}.")
            return
        }
        val credential = snapshot.items[index - 1]
        println("  name      ${credential.displayName}")
        println("  username  ${credential.username}")
        println("  url       ${credential.url}")
        println("  password  ${credential.password}")
        if (credential.notes.isNotEmpty()) println("  notes     ${credential.notes}")
        if (credential.category.isNotEmpty()) println("  category  ${credential.category}")
        if (credential.tags.isNotEmpty()) println("  tags      ${credential.tags.joinToString(", ")}")
        describe(credential.password, label = "strength")
    }

    private fun describe(password: String, label: String = "") {
        val strength = evaluator(password)
        val common = if (strength.isCommon) " - this is a known-common password" else ""
        val prefix = if (label.isEmpty()) "  " else "  ${label.padEnd(9)} "
        println("$prefix${strength.level} (${strength.entropy.toInt()} bits)$common")
    }

    private fun prompt(label: String): String? {
        print("$label: ")
        System.out.flush()
        return readlnOrNull()
    }
}

private fun readMasterPassword(label: String): CharArray? {
    val console = System.console()
    if (console != null) return console.readPassword(label)

    print("$label(no console - input will be visible) ")
    System.out.flush()
    return readlnOrNull()?.toCharArray()
}
