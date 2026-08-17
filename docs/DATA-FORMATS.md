# Data formats

Every persisted shape in the app. These are contracts against a live vault — treat
changes as migrations, not edits.

## Room schema — `vault.db`

Database version **1**, `exportSchema = false`, no migrations declared.

### Table `credentials`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `TEXT` PK | UUID v4 string, generated client-side |
| `encryptedPayload` | `BLOB` | AES-256-GCM ciphertext of the payload JSON |
| `iv` | `BLOB` | 12-byte GCM nonce for this row |
| `createdAt` | `INTEGER` | Epoch millis, device local clock |
| `updatedAt` | `INTEGER` | Epoch millis, device local clock |
| `syncedAt` | `INTEGER?` | Epoch millis of last successful push/pull; `NULL` = never synced |
| `isDeleted` | `INTEGER` | Tombstone flag; rows are soft-deleted and never purged |

`CredentialEntity` overrides `equals`/`hashCode` to compare on `id` only — deliberate,
because `ByteArray` identity comparison would break list diffing.

Two known problems recorded here so a migration can address them together:

- **`exportSchema = false` and no migrations.** Any future schema change has no
  validation and no upgrade path. Turn schema export on before the first migration.
- **`updatedAt` is overloaded.** It means "row last written", but the UI reads it as
  "password last changed" (#29). Pinning an entry or editing its notes resets it. A
  separate `passwordChangedAt` column is needed — that is the first planned migration
  (1 → 2).

## Credential payload JSON

The plaintext that gets sealed into `encryptedPayload`. Produced by
`CredentialRepositoryImpl.credentialToJson`, consumed by `jsonToCredential`.

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
  "linkedDomains":  ["github.com"]
}
```

Encoding is UTF-8. All reads use `optString` / `optBoolean` / `optJSONArray` with
defaults, so **adding a field is backward-compatible** and older payloads decode fine.
Removing or renaming a field is not.

Identity and timestamps live on the *row*, not in the payload — `id`, `createdAt`, and
`updatedAt` are read from `CredentialEntity` when reconstructing a `Credential`. Anything
that needs to be searchable without decrypting must move out of the payload; today
nothing is, which is why search decrypts the entire vault.

`VaultAutofillService` and `AutofillAuthActivity` parse this JSON independently and read
only a subset of fields. Any payload change needs those two call sites checked too.

## Backup file — format v1

Written by `ExportVaultUseCase`, read by `ImportVaultUseCase`. Pretty-printed UTF-8 JSON.

```json
{
  "version":        1,
  "exportedAt":     1755400000000,
  "salt":           "<base64, 16 bytes>",
  "iv":             "<base64, 12 bytes>",
  "encryptedVault": "<base64 ciphertext>"
}
```

`encryptedVault` decrypts (AES-256-GCM, under the **session key**) to a JSON array of
row-shaped objects:

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

### The v1 defect

The format is **doubly encrypted with two different keys, and only one of them travels
with the file.**

- The outer envelope is sealed under the session key, and the file records the `salt`
  needed to re-derive it. That part round-trips.
- Each inner `encryptedPayload` is still sealed under the *export-time master key*. The
  importer re-inserts those bytes verbatim without re-encrypting them.

So an import succeeds — the outer layer decrypts, rows land in the database — and then
every credential fails to decrypt, and the failure is swallowed into an empty list (#40).

v1 only round-trips correctly when the importing device's current master key is byte-identical
to the exporting one: **same device, same master password, unchanged salt.** The import
dialog promises more than that ("enter the master password that was used when this backup
was created"), which is finding #3.

`isDeleted` and `syncedAt` are not exported. Imported rows therefore always arrive as
live, never-synced entries — which is correct behaviour, just undocumented.

### Import modes

| Mode | Behaviour |
| --- | --- |
| Merge | For each imported row, insert only if `id` is absent locally. Existing rows win. |
| Replace | Soft-delete **every** local row, then upsert all imported rows. Local-only entries survive as tombstones and become unreachable. |

## Backup file — format v2 (planned)

Fixes #3 by collapsing to a **single** encryption layer whose key is fully described by
the file. Specified here so the implementation and its tests agree.

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
- **v1 remains importable**, read-only, on the same-key path it already works for. Any
  existing v1 backup the user holds must keep working.
- The password-to-bytes encoding for v2's Argon2 call stays the frozen UTF-16BE routine
  documented in [SECURITY.md](SECURITY.md), so one code path serves both.

## Firestore layout

```
vaults/{uid}                          ← document
  salt:                   string (base64)
  verificationCiphertext: string (base64)
  verificationIv:         string (base64)

vaults/{uid}/credentials/{credentialId}   ← subcollection
  encryptedPayload: string (base64)
  iv:               string (base64)
  createdAt:        number (epoch millis, writing device's clock)
  updatedAt:        number (epoch millis, writing device's clock)
  isDeleted:        boolean
```

`{uid}` is the Firebase Auth UID — anonymous or Google-linked. Changing accounts changes
the vault path, which is what `migrateFromAnonymousUser` exists to paper over.

`syncedAt` is deliberately local-only and never uploaded.

Two structural problems, both detailed in [SYNC.md](SYNC.md):

- `createdAt` / `updatedAt` are **client wall-clock values**, compared across devices.
  Clock skew silently drops changes (#20). They need to become server timestamps or a
  monotonic per-vault revision.
- The vault document stores the salt and verification blob, so whoever holds the account
  can attempt an offline brute force of the master password against
  `verificationCiphertext`. That is inherent to cross-device sync of this design and is
  the reason the Argon2 cost parameters matter.

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
