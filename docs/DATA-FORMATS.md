# Data formats

Every persisted shape in the app. These are contracts against a live vault — treat
changes as migrations, not edits.

## Room schema — `vault.db`

Database version **3**, `exportSchema = true` (committed under `app/schemas`),
migrations in `VaultMigrations`.

### Table `credentials`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `TEXT` PK | UUID v4 string, generated client-side |
| `encryptedPayload` | `BLOB` | AES-256-GCM ciphertext of the payload JSON |
| `iv` | `BLOB` | 12-byte GCM nonce for this row |
| `createdAt` | `INTEGER` | Epoch millis, device local clock |
| `updatedAt` | `INTEGER` | Any write to the row: edit, pin toggle, re-encryption sweep |
| `passwordChangedAt` | `INTEGER` | When the password itself last changed. Added in v2; backfilled from `createdAt` in v3 |
| `syncedAt` | `INTEGER?` | Epoch millis of last successful push/pull; `NULL` = never synced |
| `isDeleted` | `INTEGER` | Tombstone flag. Deletes are soft; sync purges tombstones both sides have seen after 30 days |

`CredentialEntity` overrides `equals`/`hashCode` to compare on `id` only — deliberate,
because `ByteArray` identity comparison would break list diffing.

Two problems from the v1 schema were addressed together in v2: schema export is now on
and every version is committed, and `passwordChangedAt` separates password rotation from
row writes (#29).

The backfill took two attempts, which is worth recording. v2 sourced it from `updatedAt`,
reasoning that it was an upper bound on when the password changed. It was — until the
master-password re-encryption sweep rewrote `updatedAt` on every row, which is exactly
what happened while testing that sweep. v3 re-backfills from `createdAt`, which the sweep
leaves alone. `createdAt` is a *lower* bound: exactly right for an entry never rotated,
too old for the rest. For a feature that exists to prompt rotation, over-warning is the
better way to be wrong.

Rows re-encrypted by a key change or the vault-key conversion carry `passwordChangedAt`
across untouched — re-encryption is not a rotation.

## Credential payload JSON

The plaintext that gets sealed into `encryptedPayload`. Read and written by
`CredentialPayloadCodec`.

```json
{
  "siteName":       "GitHub",
  "appName":        "GitHub",
  "url":            "https://github.com",
  "username":       "okan",
  "password":       "…",
  "notes":          "",
  "category":       "Development",
  "tags":           ["work", "2fa"],
  "isPinned":       false,
  "linkedPackages": ["com.github.android"],
  "linkedDomains":  ["github.com"],
  "contentChangedAt": 1750000000000
}
```

Encoding is UTF-8. All reads use `optString` / `optBoolean` / `optJSONArray` / `optLong`
with defaults, so **adding a field is backward-compatible** and older payloads decode fine.
Removing or renaming a field is not.

Identity and most timestamps live on the *row*, not in the payload — `id`, `createdAt`,
`updatedAt` and `passwordChangedAt` come from `CredentialEntity` when reconstructing a
`Credential`. Nothing searchable lives outside the payload, so filtering happens on
already-decrypted summaries in `VaultViewModel` rather than in a query.

`contentChangedAt` is the exception, and is in the payload precisely because nothing sorts
or filters by it: a new column would have needed a migration, a new payload field did not.
It records when the credential's *content* last changed, which `updatedAt` cannot — that is
the sync clock, and pinning an entry has to move it because the pin state travels in this
payload. Payloads written before the field existed decode to `updatedAt`, which is what
they were being displayed as anyway (finding #58).

`VaultAutofillService` and `AutofillAuthActivity` parse this JSON independently and read
only a subset of fields. Any payload change needs those two call sites checked too.

## Backup file — format v1 (read-only)

No longer written; still accepted by `ImportVaultUseCase` so existing files stay usable.
Pretty-printed UTF-8 JSON.

```json
{
  "version":        1,
  "exportedAt":     1755400000000,
  "salt":           "<base64, 16 bytes>",
  "iv":             "<base64, 12 bytes>",
  "encryptedVault": "<base64 ciphertext>"
}
```

`encryptedVault` decrypts (AES-256-GCM, under the key derived from the master password
of the day) to a JSON array of row-shaped objects:

```json
[
  {
    "id":               "…uuid…",
    "encryptedPayload": "<base64>",
    "iv":               "<base64>",
    "createdAt":        1750000000000,
    "updatedAt":        1755000000000
  }
]
```

### Why v1 was replaced

The format was doubly encrypted, and the importer only ever undid one layer. The outer
envelope was sealed under the export-time key and the file recorded the salt to re-derive
it; each inner `encryptedPayload` was sealed under that *same* key, but the importer
re-inserted those bytes verbatim. So an import reported success, rows landed in the
database, and every credential then failed to decrypt — swallowed into an empty list by
#40. It round-tripped only onto the exporting device with an unchanged master password,
while the dialog promised more (finding #3).

The importer now decrypts both layers and re-seals each credential under the receiving
vault's key, so **v1 files are restorable anywhere** — which they never were. Only the
backup password from the time is needed.

`isDeleted` and `syncedAt` are not present in v1 files, so imported rows arrive live and
never-synced.

### Import modes

Both formats share these, implemented in `ImportVaultUseCase`:

| Mode | Behaviour |
| --- | --- |
| Merge | Keeps existing live entries; only ids absent locally are added. |
| Replace | Writes the backup's entries and retires anything it does not mention — in the **same transaction**, so a failure part-way cannot leave the vault emptied and unfilled. |

Every imported credential is re-encrypted under the receiving vault's key with a fresh IV,
whichever mode and whichever format. `syncedAt` is never exported; imported rows arrive as
never-synced.

## Backup file — format v2

Fixes #3 by collapsing to a **single** encryption layer whose key is fully described by
the file. Written by `ExportVaultUseCase`, read by `ImportVaultUseCase`; the parsing and
validation live in `VaultBackupFormat`.

```json
{
  "version":    2,
  "exportedAt": 1755400000000,
  "kdf": {
    "algorithm":   "argon2id",
    "version":     19,
    "memoryKib":   65536,
    "iterations":  3,
    "parallelism": 4,
    "salt":        "<base64, 16 bytes>"
  },
  "iv":      "<base64, 12 bytes>",
  "payload": "<base64 ciphertext>"
}
```

`payload` decrypts to a JSON array of **plaintext** credential objects — the payload JSON
above, plus `id`, `createdAt`, `updatedAt`, and `passwordChangedAt`:

```json
[
  {
    "id": "…uuid…",
    "createdAt": 1750000000000,
    "updatedAt": 1755000000000,
    "passwordChangedAt": 1750000000000,
    "siteName": "GitHub",
    "…": "…remaining payload fields…"
  }
]
```

Design points:

- **One key, fully described.** The KDF parameters ride in the file instead of being
  implied by the app's current constants, so a future parameter change cannot orphan old
  backups.
- **The backup password is independent of the master password.** The user supplies it at
  export time. A backup stays valid after a master-password change.
- **Import re-encrypts.** Each credential is sealed under the importing device's current
  session key with a fresh IV. This is the actual fix.
- **v1 is importable and now actually restorable.** Its outer envelope and its inner
  payloads were sealed under the same key, so the backup password opens both layers; the
  importer decrypts each payload and re-seals it like any other entry. A v1 file that
  could previously only be restored onto the exporting device now restores anywhere.
- **Costs are bounded on read.** A file is untrusted input, so declared Argon2 parameters
  outside sane limits are rejected rather than attempted.
- The password-to-bytes encoding for v2's Argon2 call stays the frozen UTF-16BE routine
  documented in [SECURITY.md](SECURITY.md), so one code path serves both.

## Firestore layout

```
vaults/{uid}                          ← document
  salt:                   string (base64)
  verificationCiphertext: string (base64)
  verificationIv:         string (base64)
  vaultKeyCiphertext:     string (base64)   ← vault key, sealed under the master key
  vaultKeyIv:             string (base64)

vaults/{uid}/credentials/{credentialId}   ← subcollection
  encryptedPayload:  string (base64)
  iv:                string (base64)
  createdAt:         number (epoch millis, writing device's clock)
  updatedAt:         number (epoch millis, writing device's clock)
  passwordChangedAt: number (epoch millis, writing device's clock)
  isDeleted:         boolean
  serverUpdatedAt:   timestamp (assigned by Firestore)
```

`{uid}` is the Firebase Auth UID, always a real signed-in account. Nothing signs in
anonymously any more, so there is no anonymous vault and no migration between UIDs.

`serverUpdatedAt` is what pull ordering uses. The two epoch-millis fields are the writing
device's clock and are meaningful only on that device — comparing them across devices
silently dropped changes (#20). `syncedAt` is local-only and never uploaded.

**The wrapped vault key is what makes a second device workable.** Without it a device can
verify the master password and still not reach the key the rows are encrypted under, which
is how adopting a remote config used to orphan a vault (#4). It is sealed under the
master-derived key, so it is no more use to whoever holds the account than the verification
blob beside it.

That said, the vault document stores the salt and verification blob, so whoever holds the
account can attempt an **offline attack on the master password** at Argon2id cost per
guess. Inherent to cross-device sync of this design, and the reason the Argon2 parameters
must not be weakened. See [SYNC.md](SYNC.md).

## Preference stores

| File | Encrypted | Contents |
| --- | --- | --- |
| `vault_secure_prefs` | Yes (Keystore MasterKey) | `master_salt`, `verification_ciphertext`, `verification_iv`, `db_passphrase` |
| `biometric_prefs` | Yes (Keystore MasterKey) | `wrapped_vault_key`, `wrapped_vault_iv` |
| `sync_prefs` | No | `last_sync_time_{uid}` → `Long` |
| `autofill_dismissed_prefs` | No | `{webDomain or packageName}` → `Boolean`, permanent |
| `password_presets` | No | `presets` → JSON array of generator presets |

All base64 in preferences uses `Base64.NO_WRAP`.

The two encrypted stores are the ones that must be excluded from Android auto-backup:
their Keystore master key is not backed up, so a restored copy is undecryptable, and the
resulting failure currently triggers the destructive path in `DatabaseModule` (#1, #2).

### Generator preset JSON

```json
{
  "id": "default", "name": "Default", "isDefault": true,
  "length": 20,
  "includeUppercase": true, "includeLowercase": true,
  "includeDigits": true, "includeSymbols": true,
  "excludeAmbiguous": false,
  "customSymbols": "!@#$%^&*()-_=+[]{}|;:,.<>?"
}
```

The preset with `id == "default"` is synthesised if absent and cannot be deleted.
