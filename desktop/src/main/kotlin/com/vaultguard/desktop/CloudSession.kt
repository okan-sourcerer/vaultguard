package com.vaultguard.desktop

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.repository.VaultSnapshot
import com.vaultguard.app.util.PasswordStrengthEvaluator
import com.vaultguard.desktop.cloud.CloudVault
import com.vaultguard.desktop.cloud.DesktopConfig
import com.vaultguard.desktop.cloud.FirebaseSignIn
import com.vaultguard.desktop.cloud.FirestoreClient
import com.vaultguard.desktop.cloud.GoogleOAuth
import com.vaultguard.desktop.cloud.RemoteVaultCodec

private val evaluator = PasswordStrengthEvaluator()

/**
 * Read-only cloud mode: sign in, fetch the vault, unlock it, show what is in it.
 *
 * Nothing here writes — not to Firestore, not to disk. That is the deliberate shape of
 * this first version: the whole chain gets exercised against a live vault without being
 * able to damage one.
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

    print("Fetching credentials... ")
    System.out.flush()
    val rows = try {
        client.credentialDocuments().mapNotNull { RemoteVaultCodec.readCredentialRow(it) }
    } catch (e: Exception) {
        println()
        System.err.println("${e.message}")
        return
    }
    val snapshot = vault.decrypt(rows, vaultKey)
    println("${rows.size} ${if (rows.size == 1) "row" else "rows"}.")

    report(snapshot)
    CloudBrowser(snapshot).run()
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

private class CloudBrowser(private val snapshot: VaultSnapshot<Credential>) {

    fun run() {
        println("Read-only. Type `help` for commands.")
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
        quit            exit

        This session cannot change anything, locally or in the cloud.
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
        val strength = evaluator(credential.password)
        val common = if (strength.isCommon) " - this is a known-common password" else ""
        println("  strength  ${strength.level} (${strength.entropy.toInt()} bits)$common")
    }
}

private fun readMasterPassword(label: String): CharArray? {
    val console = System.console()
    if (console != null) return console.readPassword(label)

    print("$label(no console - input will be visible) ")
    System.out.flush()
    return readlnOrNull()?.toCharArray()
}
