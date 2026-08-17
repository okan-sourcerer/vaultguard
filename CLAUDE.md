# CLAUDE.md

Guidance for agents working in this repository.

## What this is

VaultGuard is an Android password manager (Kotlin, Compose, Hilt, Room over SQLCipher,
optional Firestore sync). Single module, `:app`.

**There is a live vault with the owner's real passwords on one device.** This is not a
scratch project. Correctness beats elegance, and any change that could orphan or corrupt
stored data needs a migration and a test, not a judgement call.

## Where things stand

A full review in August 2026 catalogued 53 defects. All eight data-loss findings, every
security and sync finding, and the build and UX work are done — see
[docs/FINDINGS.md](docs/FINDINGS.md) for the catalogue and
[docs/REMEDIATION-PLAN.md](docs/REMEDIATION-PLAN.md) for what was done in what order and
what is left.

Both debug and minified release builds have been installed and exercised on the owner's
device: vault loads, biometrics, autofill, and clipboard clearing all confirmed working.

## Read before editing

| Before touching | Read |
| --- | --- |
| Anything in `security/` | [docs/SECURITY.md](docs/SECURITY.md) — key hierarchy and frozen crypto |
| Persisted shapes, backup, Firestore | [docs/DATA-FORMATS.md](docs/DATA-FORMATS.md) |
| `data/remote/` | [docs/SYNC.md](docs/SYNC.md) |
| Anything at all | [docs/FINDINGS.md](docs/FINDINGS.md) — the defect is probably already known |
| Planning work | [docs/REMEDIATION-PLAN.md](docs/REMEDIATION-PLAN.md) |

## The key hierarchy, in one paragraph

The master password derives a **master key** (Argon2id). That key encrypts nothing but a
verification blob and the **vault key** — a random 256-bit value, generated once, which
never changes and is what actually encrypts credentials. Changing the master password
re-wraps one small blob; no credential is touched, and biometric unlock keeps working
because the key it wraps is unchanged. `sessionKey` throughout the app is the *vault* key.
Full diagram in [docs/SECURITY.md](docs/SECURITY.md).

## Frozen invariants

Changing any of these makes the existing vault permanently unreadable:

- Argon2id: `ARGON2_id`, version `0x13`, m=65536 KiB, t=3, p=4, 16-byte salt, 32-byte output.
- The password-to-bytes encoding in `KeyDerivation.toPasswordBytes()` is **UTF-16BE, not
  UTF-8**. It is non-standard and deliberate-by-now. Do not "fix" it.
- AES-256-GCM, 12-byte random IV per encryption, 128-bit tag, no AAD.
- Verification plaintext `"VAULTGUARD_VERIFY"`.
- Standard padded base64, no line wrapping, in preferences and backups.
- The preference file names `vault_secure_prefs` and `biometric_prefs`, and every key in
  them. Renaming one presents to the user as "wrong master password" against a password
  that is perfectly correct. Tests pin them.

A golden-vector test guards the derivation, checked against an independent Argon2
implementation. If it fails, you have broken every existing vault — do not update the
expected value to make it pass.

## Rules

1. **Never delete user data on an error path.** Quarantine, surface, let the user decide.
   Finding #1 was exactly this mistake, justified by a comment that was wrong.
2. **Never swallow a decryption failure into `null`.** Finding #40 is why four separate
   data-loss bugs all presented as "you have no passwords". Reads return `VaultSnapshot`,
   which carries the failures; lookups return `CredentialLookup`, which separates absent
   from unreadable from locked.
3. **Argon2 must not run on the main thread.** It blocks for hundreds of milliseconds.
   Derivation goes through `MasterPasswordManager.deriveKey`, which dispatches.
4. **`KeyDerivation.deriveKey` zeroes the `CharArray` it is given.** Callers cannot reuse
   it, including for a retry, and code needing two derivations must pass separate copies.
5. **Room's destructive migration fallback stays off.** A failed migration must crash, not wipe.
6. **The autofill package is a second front door.** `VaultAutofillService` and
   `AutofillAuthActivity` decrypt independently of `CredentialRepository`. They share
   `CredentialMatcher` and `FieldClassifier` — keep it that way; they had drifted apart
   before, and the looser copy guarded the more sensitive path.
7. **A backup that silently omits entries is worse than no backup.** Both exports refuse
   to run if any row failed to decrypt.
8. Match the surrounding code's style. Idiomatic Compose + Hilt with constructor injection
   and `StateFlow`-based UI state.

## What the tests cannot tell you

Three real bugs in this codebase were invisible to 300-plus passing tests and to reading
the code, because the platform constraint that caused them is not expressed anywhere in
the API surface:

- `getPrimaryClipDescription()` returns **null** rather than throwing when an app lacks
  focus, so a clipboard check that looked correct silently never matched;
- Android freezes cached processes, so an in-process timer never fires once the app is
  backgrounded, and a WorkManager delay is a request rather than a promise;
- browsers need a per-package `<compatibility-package>` opt-in before autofill receives
  any usable structure at all.

When a change depends on how Android *schedules* or *permits* something, rather than on
what a method signature says, assume it needs exercising on a device before it is believed.

## Commands

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

```bash
./gradlew :app:assembleRelease
```

The release build is minified and debug-signed so it can be installed locally — the same
certificate as debug, so it upgrades in place and keeps the vault. Replace the signing
config with a real keystore before distributing anything.

Anything reached only by reflection or JNI needs a keep rule in `app/proguard-rules.pro`,
and a missing one cannot fail in a debug build. Check
`build/outputs/mapping/release/usage.txt` after adding code that is loaded by name —
WorkManager workers, Room's generated implementation, and SQLCipher's native bindings all
fall into this category.

**Never uninstall to fix a failed install.** The Keystore key and encrypted preferences go
with it, and the vault becomes unrecoverable. Check the signing certificate instead.

## Testing notes

- `CryptoManager`, `KeyDerivation`, `GeneratePasswordUseCase`, `PasswordStrengthEvaluator`,
  `CredentialMatcher`, `FieldClassifier` and `SyncMerge` are pure JVM — no Robolectric.
- `org.json` is stubbed in bare unit tests; the real implementation is on the test
  classpath via `org.json:json`. Prefer `java.util.Base64` over `android.util.Base64` in
  new code — identical output for this app's flags, and not a stub in tests.
- `MasterPasswordManager` and `BiometricAuthManager` take a `SecurePrefs`; use
  `FakeSecurePrefs` off-device. `FakeBiometricKeystore` stands in for the Android Keystore
  and is backed by real AES-GCM, so replacing a key genuinely breaks prior material — one
  test asserts that, so the others mean something.
- Room migrations need `androidx.room:room-testing` and run as instrumented tests against
  the committed schemas in `app/schemas`.

## Conventions

- Findings are referenced by number (`#12`) in commits and comments; the catalogue is
  [docs/FINDINGS.md](docs/FINDINGS.md). Update the Status column when a fix lands.
- Keep documentation truthful about *current* behaviour. Several original code comments
  described intent rather than reality, and one of them justified a bug that deleted the
  vault. If you cannot make the code match the comment, fix the comment.
- Write the test that fails first where the logic is pure. Doing so caught a wrong
  password-age backfill, an off-by-one in the lockout table, and a normaliser that turned
  `password123` into `passwordi2e`.
