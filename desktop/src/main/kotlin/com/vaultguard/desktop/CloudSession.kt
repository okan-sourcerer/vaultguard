package com.vaultguard.desktop

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.PasswordGeneratorConfig
import com.vaultguard.app.domain.repository.VaultSnapshot
import com.vaultguard.app.domain.usecase.GeneratePasswordUseCase
import com.vaultguard.app.util.PasswordStrengthEvaluator
import com.vaultguard.desktop.cloud.CloudVault
import com.vaultguard.desktop.cloud.CloudConnect
import com.vaultguard.desktop.cloud.DesktopConfig
import com.vaultguard.desktop.cloud.FirestoreClient
import com.vaultguard.desktop.cloud.RemoteVaultCodec
import javax.crypto.SecretKey
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

    val open = CloudConnect.open(
        config = config,
        askPassword = { label -> readMasterPassword("$label: ") },
        say = { println(it) },
        warn = { System.err.println(it) }
    ) ?: return

    println("Vault unlocked.")

    val browser = CloudBrowser(open.vault, open.vaultKey, open.client)
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
     * The rows behind [snapshot], by id, kept for their `updateTime`.
     *
     * An edit or a delete has to say which version of the document it saw, and that value
     * only exists on the row as read. An entry added during this session is absent here
     * until the next refresh, which is why both commands check.
     */
    private var rowsById: Map<String, com.vaultguard.desktop.cloud.RemoteCredentialRow> = emptyMap()

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
        rowsById = rows.associateBy { it.id }
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
                "edit" -> edit(parts.getOrNull(1))
                "delete", "rm" -> delete(parts.getOrNull(1))
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
        edit <n>        change an entry's fields
        delete <n>      soft-delete an entry (the phone can still undo it)
        quit            exit

        Writes are checked against the version that was fetched. If the phone has
        touched an entry since, the write is refused rather than overwriting it.

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
        if (!confirm("Write this to the cloud vault?")) return

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

    private fun edit(argument: String?) {
        val existing = resolve(argument) ?: return
        val row = rowFor(existing) ?: return

        println("Blank keeps the current value.")
        val name = promptWith("Site or app name", existing.siteName) ?: return
        val username = promptWith("Username", existing.username) ?: return
        val url = promptWith("URL", existing.url) ?: return
        val notes = promptWith("Notes", existing.notes) ?: return
        val password = promptWith("Password", "unchanged") ?: return

        val newPassword = password.ifBlank { existing.password }
        val passwordChanged = newPassword != existing.password
        if (passwordChanged) describe(newPassword)

        val now = System.currentTimeMillis()
        val updated = existing.copy(
            siteName = name.ifBlank { existing.siteName },
            username = username.ifBlank { existing.username },
            url = url.ifBlank { existing.url },
            notes = notes.ifBlank { existing.notes },
            password = newPassword,
            updatedAt = now,
            // Only when the password itself moved. Bumping it on every edit would make the
            // phone's rotation prompt useless, which is what #29 separated these for.
            passwordChangedAt = if (passwordChanged) now else existing.passwordChangedAt,
            contentChangedAt = now
        )

        val unchanged = updated.siteName == existing.siteName &&
            updated.username == existing.username &&
            updated.url == existing.url &&
            updated.notes == existing.notes &&
            !passwordChanged
        if (unchanged) {
            println("Nothing changed. Nothing written.")
            return
        }

        if (!confirm("Write this change to the cloud vault?")) return

        print("Writing... ")
        System.out.flush()
        try {
            client.updateCredential(
                vault.encrypt(updated, vaultKey).copy(updateTime = row.updateTime)
            )
        } catch (e: Exception) {
            println()
            System.err.println("Not written: ${e.message}")
            return
        }

        replace(existing, updated)
        println("saved.")
    }

    private fun delete(argument: String?) {
        val existing = resolve(argument) ?: return
        val row = rowFor(existing) ?: return

        println("  ${existing.displayName}  ${existing.username}")
        // A soft delete, so this is recoverable from the phone until the tombstone is
        // purged. Said plainly rather than implied, because "delete" reads as final.
        println("This marks it deleted and syncs that to the phone, where it can still be undone.")
        if (!confirm("Delete it?")) return

        print("Deleting... ")
        System.out.flush()
        try {
            client.tombstoneCredential(row, System.currentTimeMillis())
        } catch (e: Exception) {
            println()
            System.err.println("Not deleted: ${e.message}")
            return
        }

        snapshot = snapshot.copy(items = snapshot.items.filterNot { it.id == existing.id })
        rowsById = rowsById - existing.id
        println("deleted.")
    }

    /** The row an entry was read from, or null with an explanation if there is not one. */
    private fun rowFor(credential: Credential): com.vaultguard.desktop.cloud.RemoteCredentialRow? {
        val row = rowsById[credential.id]
        if (row?.updateTime == null) {
            println("That entry was added in this session and has not been read back yet.")
            println("`refresh` first, so the write can be checked against what the server holds.")
            return null
        }
        return row
    }

    private fun replace(old: Credential, new: Credential) {
        snapshot = snapshot.copy(
            items = snapshot.items
                .map { if (it.id == old.id) new else it }
                .sortedBy { it.displayName.lowercase() }
        )
        // The stored updateTime is now stale: the document has moved on. Force a refresh
        // before this entry can be written to again, rather than letting the next edit
        // fail against a version that no longer exists.
        rowsById = rowsById - old.id
    }

    private fun resolve(argument: String?): Credential? {
        val index = argument?.toIntOrNull()
        if (index == null || index !in 1..snapshot.items.size) {
            println("Give an entry number from 1 to ${snapshot.items.size}.")
            return null
        }
        return snapshot.items[index - 1]
    }

    private fun confirm(question: String): Boolean {
        print("$question [y/N] ")
        System.out.flush()
        if (readlnOrNull()?.trim()?.lowercase() == "y") return true
        println("Cancelled. Nothing changed.")
        return false
    }

    private fun promptWith(label: String, current: String): String? {
        print("$label [$current]: ")
        System.out.flush()
        return readlnOrNull()
    }

    private fun line(index: Int, credential: Credential): String {
        val name = credential.displayName.padEnd(24)
        val user = credential.username.padEnd(24)
        return "  [${index + 1}] $name $user ${credential.url.ifEmpty { credential.siteName }}"
    }

    private fun show(argument: String?) {
        val credential = resolve(argument) ?: return
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
