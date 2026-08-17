# Security design

Describes the key hierarchy as **currently implemented**, the threat model it is meant to
satisfy, and the parameters that are frozen because a live vault depends on them.

> Read the "Frozen invariants" section before touching anything in
> `app/src/main/java/com/vaultguard/app/security/`. Changing any frozen value silently
> renders existing vaults undecryptable, and the current code reports that as an empty
> vault rather than an error (see [FINDINGS.md](FINDINGS.md) #40).

## Key hierarchy

```
master password (user input, never persisted)
        │
        │ Argon2id  m=64 MiB, t=3, p=4, out=32 B
        │ salt = 16 random bytes, persisted
        ▼
   masterKey (AES-256)  ── key-encrypting key; in memory only, briefly
        │
        ├── AES-256-GCM ──► verification blob ("VAULTGUARD_VERIFY")
        │                    checks the password without touching the vault
        │
        └── AES-256-GCM ──► wrapped vaultKey


   vaultKey (AES-256, random, generated once, never changes)
        │                ── the session key; what everything above the
        │                   security layer actually uses
        │
        ├── AES-256-GCM ──► credential payloads (per-row random IV)
        │
        ├── AES-256-GCM ──► vault-key check blob
        │                    lets a key arriving from the biometric wrapper
        │                    be verified — the main verification blob is
        │                    sealed under masterKey and cannot serve here
        │
        └── wrapped by ────► Android Keystore biometric key
                             (AES-256, userAuthenticationRequired,
                              invalidatedByBiometricEnrollment)


   dbPassphrase (32 random bytes, persisted)
        │
        └── SQLCipher ────► vault.db
```

### Why two keys

The master-derived key used to encrypt payloads directly. Changing the master password
therefore meant re-encrypting every credential — an O(n) rewrite spanning two stores that
cannot share a transaction, which is what made a half-converted vault possible
(finding #5) and what invalidated the biometric wrapper every time (finding #6).

With the indirection, a password change re-wraps one small blob in a single preferences
write. No credential is touched, there is no partially-converted state to recover from,
and biometric unlock keeps working because the key it wraps never changes.

The trade-off is one more piece of stored material and one more failure mode: if the
wrapped vault key is lost, the vault is unrecoverable even with the correct master
password. That is why it is written before payloads during conversion, never after.

### Layers at rest

Two independent layers protect a credential:

1. **SQLCipher** encrypts the whole database with `dbPassphrase`, a random value in
   Keystore-backed preferences. It is available whenever the device is unlocked and **does
   not depend on the master password**. This defends against reading the raw file.
2. **AES-GCM under `vaultKey`** encrypts each payload individually, and `vaultKey` is only
   reachable through the master password or a biometric prompt.

The consequence worth internalising: **the database opens without the master password.**
Only payload contents are gated on it.

## Where things are stored

| Data | Location | Protected by |
| --- | --- | --- |
| Credential ciphertext + IV | `vault.db`, table `credentials` | SQLCipher (`dbPassphrase`) |
| `master_salt` | EncryptedSharedPreferences `vault_secure_prefs` | Keystore MasterKey |
| `vault_key_ciphertext`, `vault_key_iv` | EncryptedSharedPreferences `vault_secure_prefs` | `masterKey` (and Keystore MasterKey) |
| `vault_key_check_ciphertext`, `vault_key_check_iv` | EncryptedSharedPreferences `vault_secure_prefs` | `vaultKey` (and Keystore MasterKey) |
| `verification_ciphertext`, `verification_iv` | EncryptedSharedPreferences `vault_secure_prefs` | Keystore MasterKey |
| `db_passphrase` | EncryptedSharedPreferences `vault_secure_prefs` | Keystore MasterKey |
| `wrapped_vault_key`, `wrapped_vault_iv` | EncryptedSharedPreferences `biometric_prefs` | Keystore biometric key |
| Biometric key material | Android Keystore, alias `vaultguard_biometric_key` | TEE / StrongBox |
| `last_sync_time_{uid}` | Plain SharedPreferences `sync_prefs` | — (non-sensitive) |
| Dismissed autofill prompts | Plain SharedPreferences `autofill_dismissed_prefs` | — (leaks visited domains) |
| Generator presets | Plain SharedPreferences `password_presets` | — (non-sensitive) |

`sessionKey` is the **vault key**. It exists only in a `@Volatile` field on the
`MasterPasswordManager` singleton and is dropped on explicit lock, auto-lock timeout,
and process death. The master-derived key is transient: it exists during unlock and a
password change, and is never retained.

## Frozen invariants

These values are baked into every existing vault. Changing one without a migration makes
that vault permanently unreadable.

### Argon2id parameters — `KeyDerivation.kt`

| Parameter | Value |
| --- | --- |
| Variant | `ARGON2_id` |
| Version | `ARGON2_VERSION_13` (0x13) |
| Memory | 65536 KiB (64 MiB) |
| Iterations | 3 |
| Parallelism | 4 |
| Salt length | 16 bytes |
| Output length | 32 bytes |

### Password-to-bytes encoding — **the sharp edge**

`KeyDerivation.toPasswordBytes()` does **not** use UTF-8. It manually encodes each
`Char` as two big-endian bytes — effectively UTF-16BE without a BOM:

```kotlin
bytes[i * 2]     = (this[i].code shr 8).toByte()
bytes[i * 2 + 1] = this[i].code.toByte()
```

For an ASCII password this produces a null byte before every character, so `"abc"`
becomes `00 61 00 62 00 63` rather than `61 62 63`. Any Argon2 implementation fed the
same password as UTF-8 derives a completely different key.

**This encoding is frozen.** It is non-standard and would not be the choice today, but
migrating away from it requires re-deriving and re-encrypting an entire vault behind a
correctly-ordered migration. Do not "fix" it opportunistically. A golden-vector test
pinning this behaviour is the first thing to land (see
[REMEDIATION-PLAN.md](REMEDIATION-PLAN.md) chunk 1).

### AEAD parameters — `CryptoManager.kt`

| Parameter | Value |
| --- | --- |
| Transform | `AES/GCM/NoPadding` |
| IV length | 12 bytes, fresh `SecureRandom` per encryption |
| Tag length | 128 bits |
| AAD | none |

IVs are stored alongside ciphertext, never derived. GCM's nonce-reuse catastrophe is
avoided by generating a new IV on every `encrypt()` call — there is no counter or
deterministic nonce anywhere, and there must never be one.

### Verification blobs

Plaintext `"VAULTGUARD_VERIFY"` (UTF-8), sealed twice:

- under `masterKey`, so a wrong master password fails fast without reading the vault;
- under `vaultKey`, so a key arriving from the biometric wrapper can be checked. The
  first blob cannot serve this purpose — biometric unlock never produces a masterKey.

Neither blob proves which key the *rows* are under. Only decrypting a row does, which
is why `UnlockVaultUseCase` probes one when the two could disagree.

## Threat model

### Defended against

| Threat | Mitigation |
| --- | --- |
| Offline extraction of `vault.db` | SQLCipher + per-payload AES-GCM; payloads need the master password |
| Device thief with an unlocked phone | Auto-lock timeout, master password / biometric gate on the vault |
| Screenshots, recents thumbnail | `FLAG_SECURE` on every activity that shows a password or takes the master password |
| Clipboard scraping | Sensitive-clip flag, WorkManager clear after 30 s (#36 — clears indiscriminately) |
| Lookalike domains and hostile package names in autofill | One matcher for both paths; hosts compared on dot boundaries, packages by explicit link or reverse-DNS derivation |
| Password reuse against known breaches | HIBP k-anonymity range query; only a 5-char SHA-1 prefix is sent |
| Cloud provider reading the vault | Only ciphertext and IVs reach Firestore; the salt is stored but useless alone |
| Brute-forcing the master password offline | Argon2id at 64 MiB makes GPU attack expensive |

### Explicitly *not* defended against

- **Rooted or compromised devices.** Keystore material can be exercised by an attacker
  with root while the device is unlocked, and `sessionKey` is readable from process memory.
- **Malicious accessibility services or keyloggers.** Nothing prevents another app with
  a11y privileges from reading typed input.
- **A forgotten master password.** There is no recovery mechanism, no escrow, no hint.
  This is intentional but is currently *undisclosed to the user* (#39).
- **Online brute force of the unlock screen.** The current backoff caps at 32 seconds and
  resets on process restart (#12).
- **Traffic analysis of HIBP queries.** The prefix reveals a 1-in-~16 bucket of the hash.

### Known live weaknesses

The threat model above describes intent. It is not currently met. The gaps are catalogued
in [FINDINGS.md](FINDINGS.md); the security-relevant ones are #9–#18, and the data-loss
ones (#1–#8) matter more than any of them because a destroyed vault fails every property
at once.

## Rules for contributors

1. **Never widen a `catch` around decryption into a silent `null`.** Failure to decrypt
   is a real event and must reach the user. The existing swallow in
   `CredentialRepositoryImpl.decryptEntity` is what turned four separate data-loss bugs
   into "you have no passwords".
2. **Never delete user data on an error path.** Quarantine, surface, and let the user decide.
3. **Argon2 must not run on the main thread.** It blocks for hundreds of milliseconds.
4. **Zero key material after use** (`ByteArray.fill(0)`, `CharArray.fill(' ')`).
   Note that `KeyDerivation.deriveKey` already zeroes the `CharArray` it is handed, so
   callers cannot reuse it — including for a retry.
5. **Any new persisted field that affects decryption needs a format version** and a
   forward-only migration.
