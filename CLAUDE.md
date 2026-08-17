# CLAUDE.md

Guidance for agents working in this repository.

## What this is

VaultGuard is an Android password manager (Kotlin, Compose, Hilt, Room over SQLCipher,
optional Firestore sync). Single module, `:app`.

**There is a live vault with the owner's real passwords on one device.** This is not a
scratch project. Correctness beats elegance, and any change that could orphan or corrupt
stored data needs a migration and a test, not a judgement call.

## Read before editing

| Before touching | Read |
| --- | --- |
| Anything in `security/` | [docs/SECURITY.md](docs/SECURITY.md) — records frozen crypto parameters |
| Persisted shapes, backup, Firestore | [docs/DATA-FORMATS.md](docs/DATA-FORMATS.md) |
| `data/remote/` | [docs/SYNC.md](docs/SYNC.md) |
| Anything at all | [docs/FINDINGS.md](docs/FINDINGS.md) — the defect is probably already known |
| Planning work | [docs/REMEDIATION-PLAN.md](docs/REMEDIATION-PLAN.md) — chunk order matters |

## Frozen invariants

Changing any of these makes the existing vault permanently unreadable:

- Argon2id: `ARGON2_id`, version `0x13`, m=65536 KiB, t=3, p=4, 16-byte salt, 32-byte output.
- The password-to-bytes encoding in `KeyDerivation.toPasswordBytes()` is **UTF-16BE, not
  UTF-8**. It is non-standard and deliberate-by-now. Do not "fix" it.
- AES-256-GCM, 12-byte random IV per encryption, 128-bit tag, no AAD.
- Verification plaintext `"VAULTGUARD_VERIFY"`.
- `Base64.NO_WRAP` everywhere in preferences and backups.

A golden-vector test guards the derivation. If it fails, you have broken every existing
vault — do not update the expected value to make it pass.

## Rules

1. **Never delete user data on an error path.** Quarantine, surface, let the user decide.
   Finding #1 is exactly this mistake, justified by a comment that was wrong.
2. **Never swallow a decryption failure into `null`.** Finding #40 is why four separate
   data-loss bugs all presented as "you have no passwords".
3. **Argon2 must not run on the main thread.** It blocks for hundreds of milliseconds.
4. **`KeyDerivation.deriveKey` zeroes the `CharArray` it is given.** Callers cannot reuse
   it, including for a retry. Same for `MasterPasswordManager.unlock`.
5. **Room's destructive migration fallback stays off.** A failed migration must crash, not wipe.
6. **The autofill package is a second front door.** `VaultAutofillService` and
   `AutofillAuthActivity` decrypt independently of `CredentialRepository`. Payload or
   matching changes must be applied in both places.
7. Match the surrounding code's style. It is idiomatic Compose + Hilt with constructor
   injection and `StateFlow`-based UI state; keep it that way.

## Commands

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

Release builds are minified and the ProGuard rules are currently wrong (#41) — a release
build is not known to work. Verify before relying on one.

## Testing notes

- `CryptoManager`, `KeyDerivation`, `GeneratePasswordUseCase`, and
  `PasswordStrengthEvaluator` are pure JVM — no Robolectric needed.
- `android.util.Base64` and `org.json` are **not** available in bare unit tests. Use
  Robolectric, or add `org.json:json` as a test dependency.
- `MasterPasswordManager` constructs `EncryptedSharedPreferences` inline; it needs the
  `SecurePrefs` seam to be testable off-device.
- Room migrations need `androidx.room:room-testing` and run as instrumented tests.

## Conventions

- Findings are referenced by number (`#12`) in commits and comments; the catalogue is
  [docs/FINDINGS.md](docs/FINDINGS.md). Update the Status column when a fix lands.
- Keep documentation truthful about *current* behaviour. Several existing code comments
  described intent rather than reality, and one of them justified a bug that deletes the
  vault. If you cannot make the code match the comment, fix the comment.
