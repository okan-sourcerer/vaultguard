# CLAUDE.md

Guidance for agents working in this repository.

## What this is

VaultGuard is an Android password manager (Kotlin, Compose, Hilt, Room over SQLCipher,
optional Firestore sync). Three modules:

- **`:core`** — pure JVM. Key derivation, AES-GCM, the credential payload contract, the
  backup format, the generator, the merge rules. No Android anywhere in it, so a desktop
  client links the same classes rather than reimplementing them.
- **`:app`** — everything Android: UI, Room, autofill, Keystore, Firebase.
- **`:desktop`** — a JVM client for the same vault, in three shapes: a CLI over a v2 backup
  file, a CLI over the Firestore vault the phone publishes, and a tray service that holds the
  vault open for a browser extension. Full read/write against the cloud vault. No Keystore
  and no local database; the only thing it persists is a Firebase refresh token, sealed
  under the master key.

Plus `extension/` — a WebExtension for Chrome and Firefox that fills from the tray service
over native messaging. It holds no key and decrypts nothing.

`:core` and `:app` share the package namespace (`com.vaultguard.app.*`) because the split
between them is by platform dependency, not by name; `:desktop` is new code and lives in
`com.vaultguard.desktop`.

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

The `:desktop` CLI has been exercised against real data in both of its modes: a backup
exported from that device, and the live Firestore vault over `--cloud`. Both derive the
key, open the vault and show the credentials correctly. The shared `:core` classes are
therefore confirmed to port off Android, not merely assumed to.

The cloud path only worked after #64 — a vault config published before the vault-key
conversion never had its wrapped key republished, so a second client could verify the
master password and reach nothing. That defect was invisible to 350-odd passing tests and
to the phone, which reads its own local copy. A second client is what found it.

## Read before editing

| Before touching | Read |
| --- | --- |
| Anything in `security/` | [docs/SECURITY.md](docs/SECURITY.md) — key hierarchy and frozen crypto |
| Persisted shapes, backup, Firestore | [docs/DATA-FORMATS.md](docs/DATA-FORMATS.md) |
| `data/remote/` | [docs/SYNC.md](docs/SYNC.md) |
| Anything at all | [docs/FINDINGS.md](docs/FINDINGS.md) — the defect is probably already known |
| Planning work | [docs/REMEDIATION-PLAN.md](docs/REMEDIATION-PLAN.md) |
| The tray on Linux | [docs/GNOME-TRAY-PLAN.md](docs/GNOME-TRAY-PLAN.md) — phases, decisions taken, what is done |
| Verifying on the device | [docs/USABILITY-PASS.md](docs/USABILITY-PASS.md) — the walkthrough script |

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
./gradlew test :app:testDebugUnitTest
```

(`test` covers `:core`; the Android module needs its own task.)

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

```bash
./gradlew :app:assembleRelease
```

```bash
./gradlew :desktop:installDist
```

Produces `desktop/build/install/vaultguard/bin/vaultguard`. Run it from a real terminal —
without a console the JVM cannot suppress echo, so a password is typed in the clear (the CLI
says so rather than pretending). `--help` lists the modes.

```bash
./gradlew :desktop:packageApp
```

`jpackage` — already in the JDK — for a `VaultGuard.exe` Windows can name and draw. Without
it the tray service is `javaw.exe` with a coffee cup in Task Manager. The image lands under
`build/`, which `clean` removes, so `--install-service --to <dir>` copies it somewhere
stable first; a Run key pointing into a deleted build fails silently at the next login.

```bash
./gradlew :desktop:packageInstaller
```

The installer for the platform the build runs on: `.msi` (needs WiX 3 on the PATH), `.dmg`,
or `.deb`, under `desktop/build/installer/`; `-Pvaultguard.installerType=rpm` on a Linux
with `rpmbuild`. `.github/workflows/release.yml` builds all four plus the signed APK for every `v*` tag and attaches them to a GitHub Release. An
installed copy has two launchers: `VaultGuard` (windowed, the tray) and `vaultguard-cli`
(console, for every `--` command). Run `vaultguard-cli --install-service --at-login` after
installing so the Run key points at the installed copy rather than at `build/`.

```bash
./gradlew syncExtension packageExtensions
```

`extension/shared` is the source; the per-browser directories are copies plus a manifest.
Run `syncExtension` after editing anything shared. See [extension/README.md](extension/README.md).

The release build is minified. Locally it is debug-signed so it can be installed — the
same certificate as debug, so it upgrades in place and keeps the vault. In CI it is signed
with the release keystore from the repository secrets (`VAULTGUARD_KEYSTORE*` in the
environment). **The two do not upgrade each other**: Android identifies an app by its
certificate, and the phone that holds the live vault stays on whichever key it was
installed with.

Anything reached only by reflection or JNI needs a keep rule in `app/proguard-rules.pro`,
and a missing one cannot fail in a debug build. Check
`build/outputs/mapping/release/usage.txt` after adding code that is loaded by name —
WorkManager workers, Room's generated implementation, and SQLCipher's native bindings all
fall into this category.

**Never uninstall to fix a failed install.** The Keystore key and encrypted preferences go
with it, and the vault becomes unrecoverable. Check the signing certificate instead.

## The desktop client, in one paragraph

`--cloud` and `--service` both reach the vault through `CloudConnect`, using Firebase and
OAuth identifiers baked into the jar at build time (`bakedDefaults` in
`desktop/build.gradle.kts`; `~/.vaultguard/desktop.properties` overrides key by key): derive
the master key against a locally stored salt, open the saved Firebase refresh token with it, refresh the
session, fetch `vaults/{uid}`, check the salt still matches, unwrap the vault key. One
Argon2id run serves the token and the vault. Writes are conditional on the document version
that was read, so a race with the phone is refused rather than merged — `SyncMerge`'s
conflict rules still have exactly one caller, on the phone. The tray holds the unlocked
vault for the browser extension and drops it after fifteen minutes idle, measured on a
monotonic clock from the last *use* of the vault rather than from any user input.

## Feedback

Both clients can post to a feedback hub, and `FeedbackReport` in `:core` is the whole of
what they send: type, message, version, environment, platform, OS, and on Android the
device model, locale and timezone. No logs, no stack traces, no metadata, no account or
install id — a test pins that list. The hub accepts more; a password manager must not
send it. The JSON is rendered verbatim on the screen before the user presses Send. The
endpoint and key come from `vaultguard.feedbackUrl`/`feedbackKey` (or the
`VAULTGUARD_FEEDBACK_*` environment) at build time; absent, the entry points are hidden.

## Testing notes

- Everything in `:core` is pure JVM by construction — no Robolectric, no android.jar.
  `CredentialMatcher` and `FieldClassifier` are still in `:app`: `FieldClassifier` needs
  `android.text.InputType`, and splitting the pair across modules is how the two matchers
  drifted apart last time (#11).
- `org.json` is a `compileOnly` dependency in `:core` — Android ships it in the framework,
  so an `implementation` copy would put a second one on the runtime classpath. `:core`'s
  tests get the real jar, and any new consumer (a desktop module) must declare its own.
  Prefer `java.util.Base64` over `android.util.Base64` in new code — identical output for
  this app's flags, and not a stub in tests.
- `MasterPasswordManager` and `BiometricAuthManager` take a `SecurePrefs`; use
  `FakeSecurePrefs` off-device. It is a **test fixture of `:core`**
  (`core/src/testFixtures`), so `:app` reaches it through
  `testImplementation(testFixtures(project(":core")))` rather than keeping a second copy.
  `FakeBiometricKeystore` stands in for the Android Keystore
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
