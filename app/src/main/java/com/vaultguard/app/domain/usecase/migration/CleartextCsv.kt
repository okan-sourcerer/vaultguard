package com.vaultguard.app.domain.usecase.migration

import com.vaultguard.app.domain.model.Credential

/**
 * TEMPORARY — CLEARTEXT MIGRATION AID. Scheduled for deletion.
 *
 * Reads and writes an **unencrypted** CSV of the whole vault so credentials can be moved
 * into another password manager while VaultGuard's own backup format is being repaired
 * (finding #3). Delete this entire `migration` package once the move is done and backup
 * v2 has landed — see `docs/REMEDIATION-PLAN.md` chunk 7.
 *
 * Column layout matches Bitwarden's CSV import, which KeePassXC, 1Password and Proton Pass
 * can also consume (KeePassXC via its column-mapping dialog):
 *
 * ```
 * folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
 * ```
 *
 * VaultGuard concepts with no Bitwarden equivalent — tags, linked packages, linked
 * domains — are written into the `fields` column as newline-separated `name: value`
 * pairs, which is how Bitwarden represents custom fields.
 *
 * Encoding is RFC 4180: fields containing a comma, double quote, CR or LF are wrapped in
 * double quotes and embedded quotes are doubled. This matters more than it looks —
 * generated passwords routinely contain commas and quotes, and naive joining corrupts
 * them silently.
 */
object CleartextCsv {

    val HEADER = listOf(
        "folder", "favorite", "type", "name", "notes", "fields",
        "reprompt", "login_uri", "login_username", "login_password", "login_totp"
    )

    private const val CRLF = "\r\n"

    /** A parsed row, kept deliberately loose so a hand-edited file still imports. */
    data class Row(
        val name: String,
        val username: String,
        val password: String,
        val uri: String,
        val notes: String,
        val folder: String,
        val favorite: Boolean,
        val tags: List<String>,
        val linkedPackages: List<String>,
        val linkedDomains: List<String>
    )

    fun write(credentials: List<Credential>): String = buildString {
        append(HEADER.joinToString(",") { escape(it) })
        append(CRLF)
        for (c in credentials) {
            val row = listOf(
                c.category,
                if (c.isPinned) "1" else "0",
                "login",
                c.displayName,
                c.notes,
                buildFields(c),
                "0",
                c.url,
                c.username,
                c.password,
                ""
            )
            append(row.joinToString(",") { escape(it) })
            append(CRLF)
        }
    }

    fun read(text: String): List<Row> {
        val records = parse(text).filter { it.any(String::isNotEmpty) }
        if (records.isEmpty()) return emptyList()

        val header = records.first().map { it.trim().lowercase() }
        val body = records.drop(1)

        fun List<String>.column(name: String): String {
            val index = header.indexOf(name)
            return if (index in indices) this[index] else ""
        }

        return body.map { record ->
            val fields = parseFields(record.column("fields"))
            Row(
                name = record.column("name"),
                username = record.column("login_username"),
                password = record.column("login_password"),
                uri = record.column("login_uri"),
                notes = record.column("notes"),
                folder = record.column("folder"),
                favorite = record.column("favorite").let { it == "1" || it.equals("true", true) },
                tags = fields["tags"].orEmpty(),
                linkedPackages = fields["linked packages"].orEmpty(),
                linkedDomains = fields["linked domains"].orEmpty()
            )
        }
    }

    private fun buildFields(c: Credential): String = buildList {
        if (c.tags.isNotEmpty()) add("tags: ${c.tags.joinToString(", ")}")
        if (c.linkedPackages.isNotEmpty()) add("linked packages: ${c.linkedPackages.joinToString(", ")}")
        if (c.linkedDomains.isNotEmpty()) add("linked domains: ${c.linkedDomains.joinToString(", ")}")
    }.joinToString("\n")

    private fun parseFields(raw: String): Map<String, List<String>> =
        raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim().lowercase()
                val values = line.substring(separator + 1)
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                if (values.isEmpty()) null else key to values
            }
            .toMap()

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** RFC 4180 parser. Handles quoted fields containing separators, quotes and newlines. */
    private fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            record.add(field.toString())
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            records.add(record)
            record = mutableListOf()
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch == ',' -> endField()
                !inQuotes && ch == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    endRecord(); i++
                }
                !inQuotes && (ch == '\n' || ch == '\r') -> endRecord()
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }
}
