# Architecture

Android app in three Gradle modules, Kotlin, Jetpack Compose, Hilt, Room over SQLCipher,
optional Firestore sync. Loosely layered along Clean Architecture lines.

## Modules

```
:core   pure JVM — no Android on the classpath at all
        security/    KeyDerivation, CryptoManager, MasterPasswordManager, SecurePrefs (interface)
        domain/      models, repository contracts, GeneratePasswordUseCase, VaultBackupFormat
        data/        CredentialPayloadCodec, SyncMerge
        util/        PasswordStrengthEvaluator, MasterPasswordPolicy, CommonPasswords
           ▲                        ▲
           │                        │ implementation(project(":core"))
           │                        │
:app    everything Android      :desktop   JVM CLI over the backup file
        ui/, Room, autofill/,              com.vaultguard.desktop
        Keystore, Firebase, Hilt
```

The split is by platform dependency, not by name: packages stay `com.vaultguard.app.*` on
both sides, so moving a file between them changes no imports.

The reason for it is that a second client — a desktop app — must not carry its own copy of
the derivation or the payload format. A frozen Argon2id configuration and a UTF-16BE
password encoding are only safe while exactly one implementation exists, and the
golden-vector test guards that one. `SecurePrefs` is the seam that lets the most
security-critical class, `MasterPasswordManager`, sit in `:core`: the Keystore-backed
implementation (`EncryptedSharedPrefs`) stays in `:app`.

### `:desktop`

A CLI that opens a format-v2 backup, generates passwords, adds entries and writes the file
back. `BackupFile.write` performs the same sequence as `ExportVaultUseCase` — fresh salt,
derive, `writeEntries`, encrypt, `writeEnvelope` — because both call the same `:core`
code; the desktop side adds only file I/O and an atomic replace.

What it deliberately does **not** have, and what a fuller client would need to answer for:

| Missing | Why it is not a gap yet |
| --- | --- |
| A merge decision of its own | Writes are conditional on the version that was read, so a race is refused rather than reconciled. Only the phone merges. |
| A key at rest | Nothing is persisted. The password is derived per operation and the array is consumed by `KeyDerivation`, so no key outlives the call. |
| SQLCipher's layer | There is no local database. The only thing on disk is the backup, which is already encrypted end to end. |
| Clipboard handling | `show` prints to stdout. None of the Android clipboard-clearing machinery (#31, #46) applies or exists here. |

### Cloud mode

`--cloud` signs in with Google over a loopback OAuth redirect, exchanges the resulting
`id_token` for a Firebase session through the Identity Toolkit REST API, and reads
`vaults/{uid}` over the Firestore REST API. The session is an ordinary user session, so the
security rules stay in force — deliberately, rather than reaching for a service account
that would sidestep them.

Unlocking does not reimplement the key hierarchy. `CloudVault` feeds the fetched
configuration into `MasterPasswordManager.adoptRemoteSetup` and then follows the phone's
path: derive the master key, verify it, unwrap the vault key. A configuration with no
wrapped vault key is refused outright rather than half-opened, which is finding #4 stated
as a precondition.

`SecurePrefs` on this side is in-memory and dies with the process. There is no Keystore to
protect a stored salt or wrapped key with, so rather than inventing a weaker at-rest story
no vault material is written at all.

### Staying signed in

One thing does survive a run: the Firebase **refresh token**, sealed under the master key in
`~/.vaultguard/desktop-session.json`. Without it every launch meant a full browser sign-in,
and — worse — a Firebase `idToken` expires after an hour, so a long session started failing
writes with a 401.

The ordering is what makes this work with a single derivation. The token is what reaches
Firestore, and Firestore is what supplies the salt, so the salt cannot come from the vault
document on this path — it is stored beside the token, in the clear. That is safe: the salt
is not secret, and the same value sits in the vault document and in the phone's preferences.
So a launch derives once, and that key both opens the saved token and unwraps the vault key.

Storing it in the clear the way `gcloud`, `gh` and `aws` do would have been a poor trade
here, because the master password has to be typed anyway to open the vault — sealing the
token under the key it derives costs the user nothing.

What it exposes, stated plainly: the file is inert without the master password and decrypts
nothing, but it is a **new offline-attackable artifact on the local disk** where the client
previously persisted none. Master passwords can be tried against it at Argon2id cost per
guess, exactly as against the verification blob in Firestore. Same class of exposure, same
mitigation. `--cloud-signout` removes it.

Two ways the saved session goes stale, both handled by falling back to a full sign-in rather
than failing: the refresh token is revoked or expires, and the master password changes on
the phone — which re-salts the vault, so the stored salt no longer matches and the key
derived from it is discarded rather than tried.

Rows that fail to decrypt are reported before anything is listed, never dropped — the
`VaultSnapshot` contract from #40 applies here exactly as it does on the phone.

The listing is a **snapshot**, not a live view. It is fetched once at unlock and again on
`refresh`, and nothing subscribes to changes — so an entry deleted on the phone stays
listed until the next fetch. That is the shape a CLI wants (every fetch is a decryption
pass over the whole vault), but it has to be said out loud, because a password manager
showing an entry that no longer exists is showing something untrue. `add` appends locally
after the server confirms the write rather than refetching.

Tombstones are what make a deletion propagate: the phone soft-deletes and pushes
`isDeleted: true`, and `CloudVault.decrypt` filters those rows out. So a `refresh` after a
phone-side delete drops the entry, and no separate delete path is needed on this side.

### The tray service

`--service` runs resident: a tray icon whose menu unlocks, refreshes, locks, signs out and
quits. It exists because the browser extension needs something to talk to, and building it
first forces the question the CLI could dodge — a session the user sits in front of and
closes is not the same as a process that stays running.

So auto-lock is answered here rather than deferred. Fifteen minutes idle, where **idle means
since the vault was last used**, not since the user last touched a keyboard: a background
service has no interaction to measure, and the meaningful question is how long a key has sat
in memory unread. Every read goes through `VaultService.credentials()`, which resets the
timer — otherwise a consumer serving credentials steadily would still be locked out from
under itself.

The timer runs on a **monotonic** clock. Wall-clock time jumps for daylight saving, NTP
corrections and users, and this codebase has been bitten by clock comparisons before (#20).

`VaultService` holds all the state and makes all the decisions; `TrayApp` only draws. That
split is not tidiness — see below.

### Search and the clipboard

`Search...` on the tray menu finds a credential by name, username or URL, copies a field,
and creates, edits or soft-deletes an entry — the same operations `--cloud` has, driven from
a window rather than a prompt.

Writes go through `VaultService`, not from the dialog: it owns the `updateTime` precondition
that makes an edit refuse rather than overwrite, and the refresh that follows. That refresh
is not only for the display — a newly created row has no `updateTime` until it has been read
back, and without one it could not then be edited.

`From window...` lists the applications currently open and filters by the one picked. The
user chooses; nothing watches. An earlier sketch had the service notice launches and offer
credentials unprompted, which means a resident process keeping a record of what you run, for
a feature that works as well on request. The process name is what seeds the search — a
window title is whatever the application felt like (`Inbox (14) - okan@example.com - Mozilla
Thunderbird`) and matches nothing, while the process name is short, stable and usually what
the entry is named after. Windows-only so far, through PowerShell rather than a native
binding, because it runs on a button press rather than in a loop.

Free-text search lives **here and not in the browser extension**, deliberately. The
extension's `match` is bound to the tab's host, and that binding is what stops a compromised
extension from enumerating the vault; adding a search action to the bridge would spend that
property permanently. The tray already holds the vault and is unreachable from a page, so
the same capability costs nothing on this side.

The window never renders a password — it goes from the vault to the clipboard without
passing through a widget, because a Swing component keeps its own copy of what it displays.

`ClipboardGuard` takes the secret back after 30 seconds, **but only if the clipboard still
holds it**. Between the copy and the timeout the user may have copied something else, and a
password manager that wipes that is one people switch off (#31, #46 taught this on Android).
What it cannot do is reach Windows clipboard history: the honest claim is "removed from the
clipboard", not "unrecoverable".

### The name and the icon

`gradlew :desktop:packageApp` runs `jpackage` — which ships with the JDK, so no new tooling
— to produce `VaultGuard.exe` with its own name, version and icon. Without it the process
holding the vault open is `javaw.exe` with a coffee cup beside it in Task Manager,
indistinguishable from any other JVM on the machine. `--install-service` prefers this
launcher when it exists and falls back to `javaw` when it does not.

`VaultIcon` draws the padlock, and one drawing serves the tray, the dialog windows and the
`.ico` the launcher is built with — including writing the `.ico` itself, PNG-per-entry, so
there is no binary asset in the repository to keep in step. `--write-icon` is the hidden
entry point the build uses; given a `.png` path it writes one of those instead, for Linux.

### The installers

`gradlew :desktop:packageInstaller` is the same `jpackage` invocation with a different
`--type`: `.msi` on Windows, `.dmg` on macOS, `.deb` on Linux. Per-user, with a Start Menu
entry and an *Apps & features* entry, so it installs and uninstalls the way anything else
does. Windows Installer recognises a newer MSI as an upgrade through a fixed
`--win-upgrade-uuid`; changing that constant would make every version install beside the
last. The MSI needs the WiX 3 toolset on the PATH — jpackage on JDK 21 does not recognise
WiX 4 — and the build says so by name rather than passing on jpackage's "Can not find WiX
tools".

The image carries two launchers. `VaultGuard` is a windowed process and starts the tray
service; `vaultguard-cli` is the same program with a console, for `--install-service`,
`--install-bridge`, `--cloud` and the rest — a windowed launcher runs them silently and
shows nothing. The browser wrapper calls the console one too: browsers spawn native hosts
without a window, so nothing flashes.

Code that needs to know where it is installed asks `InstalledImage`, which looks at the
executable that started the process rather than at the jar's location. The jar walk
(`lib/desktop.jar` → the Gradle distribution) is wrong inside an installed image, where the
jar sits under `app/` and there is no `bin/`; before this, `--install-service` reported
that the application could not be found, from inside the application.

Nothing signs the installers. Windows SmartScreen and macOS Gatekeeper both warn about
unsigned downloads; that needs a code-signing certificate, which is a purchase rather than a
build change. The GitHub Actions release workflow builds all three on the matching runners
and attaches them, with the Android APK, to a release for each `v*` tag.

### Running at login

`--install-service` writes a `.cmd` launcher into `~/.vaultguard` that starts the native
launcher (or, without one, the JVM through `javaw.exe`) and prints the `reg add` for the
`HKCU\...\CurrentVersion\Run` key, which runs that command line at login with no console
— not even briefly, which a `.bat` in the Startup folder cannot manage. The command is
printed rather than run: it is a persistent change to what happens at login, and the
same policy applies as to the native-messaging registration.

An earlier version wrote a VBScript into the Startup folder, which is the usual advice
and fails on any machine where Windows Script Host is disabled by policy. `--install-service`
removes that file if it finds one.

From the Gradle distribution the launcher lives under `build/`, which `clean` removes,
leaving a Run key that fails silently at the next login; `--to <dir>` copies the image
somewhere stable first. From an installed copy there is nothing to copy and `--to` is
ignored.

On macOS the same command generates a LaunchAgent plist, on Linux an XDG autostart
`.desktop` entry — the text lives in `Autostart`, pure functions with tests, because the
machines that consume those files are not the one this is developed on and a malformed
plist fails at login with nothing to say. Both land in `~/.vaultguard` with the `cp` (and
`launchctl bootstrap`) printed rather than run, for the same reason as the Run key. Neither
has been exercised on its platform yet.

### Where the tray will and will not appear

`java.awt.SystemTray` speaks the XEmbed system-tray protocol.

| Platform | Works |
| --- | --- |
| Windows | Yes |
| macOS | Yes (menu bar) |
| KDE, XFCE, Cinnamon, MATE | Yes |
| GNOME | **No** — the protocol was removed in 3.26 |

GNOME is the common Linux default, and its replacement (StatusNotifierItem, over DBus) is
not something AWT speaks; the AppIndicator extension does not bridge to it either. Nothing
in `VaultService` depends on any of this, so supporting those desktops is a replacement for
`TrayApp` — a DBus implementation, or a library like dorkbox SystemTray — rather than a
rewrite. `SystemTray.isSupported()` is checked at startup and says so plainly instead of
starting an invisible process.

### Writing from the desktop

Three operations: create, update, soft-delete. All go through Firestore's `:commit`
endpoint rather than a plain `PATCH`, because only a commit carries an `updateTransforms`
and `serverUpdatedAt` is not optional (see below).

Each carries a **precondition the server enforces**, and they differ:

| Operation | Precondition | Why |
| --- | --- | --- |
| create | `exists = false` | A fresh UUID should never collide. If it does, something is wrong and stopping is the only safe answer. |
| update | `updateTime` == the value read | The desktop holds a snapshot. Without this, an edit would silently discard whatever the phone wrote since the listing was fetched. |
| delete | `updateTime` == the value read | Same race, same answer. |

That precondition is why this client never needs merge logic. `SyncMerge` reconciles *after
the fact*, because the phone discovers conflicts when it pulls; the desktop is writing live
against a known version, so it can simply refuse and tell the user to refresh. Only one
side of the system makes merge decisions, which is the point.

An update rewrites the whole document — the ciphertext, the IV and the clocks move together
and a partial write would leave a payload that no longer matches its IV. A delete uses an
`updateMask` limited to `isDeleted` and `updatedAt`, so the ciphertext survives: it is a
tombstone exactly as the phone writes one, still undoable there, and the 30-day purge
remains the only thing that removes data.

`passwordChangedAt` moves only when the password itself moves. Bumping it on every edit
would make the phone's rotation prompt useless, which is what #29 separated the two clocks
for.

`serverUpdatedAt` is not optional on any of them: the phone pulls with an `orderBy` on that
field, and Firestore omits documents lacking an ordered field from query results, so a row
written without it would be invisible to the phone for ever while looking perfectly correct
in the console. A delete that never reaches the phone is the worst version of that.

A backup exported from the owner's device has been opened with this CLI — key derived,
vault unlocked, credentials shown correctly. That is the check the fixture below cannot
make: the fixture proves the pipeline has not moved since it was written, while only a
file from the phone proves the two implementations agreed in the first place.

`desktop/src/test/resources/sample-vault.vgbackup` is a committed encrypted fixture,
written by the CLI itself, opened by a test under a known password. It pins the Argon2id
parameters, the UTF-16BE encoding, the AES-GCM layer, the envelope shape and the payload
JSON in one artefact. Like the golden vector, a failure means real backups have stopped
opening — do not regenerate it to make it pass.

## Dependency flow

```
      ui/  ──────────────────────────────┐
   (Compose screens + ViewModels)        │
        │                                │
        ▼                                ▼
   domain/usecase/  ───────────────►  domain/repository/   (interfaces)
        │                                    ▲
        │                                    │ bound by di/AppModule
        ▼                                    │
   data/repository/  ───────────────────────-┘
        │
        ├──► data/local/db/     Room DAO + entity, SQLCipher-backed
        └──► data/remote/       FirebaseSyncService

   security/   cross-cutting; injected into every layer above
   autofill/   parallel entry point — bypasses ui/ and domain/ entirely
```

Two deviations from the layering worth knowing about, because they are load-bearing
rather than accidental:

- **Use cases reach past the repository.** `ExportVaultUseCase`, `ImportVaultUseCase`, and
  `ChangeMasterPasswordUseCase` inject `CredentialDao` directly. They operate on
  *ciphertext* — moving rows without decrypting them — which the `Credential`-shaped
  repository interface cannot express.
- **The autofill package is a second front door.** `VaultAutofillService` and
  `AutofillAuthActivity` inject `CredentialDao` + `CryptoManager` and do their own
  decryption. They do not go through `CredentialRepository`. They *do* share
  `CredentialMatcher` and `FieldClassifier`, which they did not always: the two matchers
  drifted apart, and the looser one guarded the locked-vault path (#11).

## Process entry points

| Entry point | Trigger | Notes |
| --- | --- | --- |
| `VaultGuardApp` | Process start | Loads the SQLCipher native lib, plants Timber in debug, registers auto-lock |
| `MainActivity` | Launcher icon | `FragmentActivity` (required by `BiometricPrompt`), sets `FLAG_SECURE`, hosts the whole Compose nav graph |
| `VaultAutofillService` | System autofill request | Runs in the same process; sees the same `MasterPasswordManager` singleton |
| `AutofillAuthActivity` | Autofill dataset auth | Unlocks the *global* vault as a side effect |
| `AutofillSaveActivity` | Autofill save request | Launched by the system from an `IntentSender`; a service may not start an activity from the background (#47) |
| `ClipboardClearService` | Copy action, +30 s | Foreground service; the only mechanism not stopped by process freezing |
| `ClearClipboardWorker` | WorkManager, +60 s | Long-stop if the service is killed |

Because the autofill service shares the process and the `MasterPasswordManager` singleton,
unlocking from an autofill prompt unlocks the vault everywhere, and the auto-lock timer is
driven by `ProcessLifecycleOwner` across all of it.

## Locking model

`MasterPasswordManager` holds `sessionKey` in a `@Volatile` field and is the single source
of truth for lock state.

```
  locked ──unlock(password) / unlockWithKey(bioKey)──► unlocked
    ▲                                                     │
    │                                                     │
    └── lockVault() ◄── explicit lock                     │
        ▲              auto-lock timeout                  │
        └──────────────process death────────────────------┘
```

Unlock state propagates to the UI through two one-shot `StateFlow`s that the consumer must
acknowledge:

- `vaultLocked` → `consumeLockEvent()` — `MainActivity` observes it and navigates to the
  unlock screen, clearing the back stack.
- `pendingLockMessage` → `consumeLockMessage()` — a message shown on the unlock screen
  after a programmatic lock (currently only "existing vault found, re-unlock").

`VaultAutoLock` is a `DefaultLifecycleObserver` on `ProcessLifecycleOwner`. It schedules a
`delay(timeoutMinutes)` coroutine on `onStop` and cancels it on `onStart`. The timer only
runs while the process is alive, which is safe because process death drops the session key
anyway. The timeout is persisted in `AppPreferences`; it used to live in a field and reset
to five minutes on every launch (#25).

Failed unlock attempts are throttled by `UnlockThrottle`, which persists to the encrypted
preferences and is applied inside `UnlockVaultUseCase`, so every unlock path — including
the autofill one, which previously had none — inherits it.

## Threading

| Work | Where it runs |
| --- | --- |
| Argon2id derivation | `@CryptoDispatcher` (`Dispatchers.Default`), injected so tests can assert it is dispatched rather than run inline |
| Bulk re-encryption | Same, in memory, before a single transactional write |
| Payload encrypt/decrypt | Caller's thread — cheap per row |
| Room suspend DAO calls | Room's own executor |
| Autofill fill and save | `Dispatchers.IO` on the service's scope; `SaveCallback` is answered asynchronously |
| Firestore | Play Services callbacks, bridged with `.await()` |
| Clipboard clear | A `shortService` foreground service — see below |

Argon2id used to run on the main thread on every unlock and setup, and `onSaveRequest`
decrypted the whole vault inside `runBlocking` there (#17, #18).

The clipboard clear is worth its own note. It ran first on an in-process timer, then on a
WorkManager job, and neither fired: Android freezes cached processes within seconds of
backgrounding, and a delayed WorkManager job is batched into maintenance windows. Only a
foreground service is exempt from both, which is why a clipboard timer needs one.

## Reactive data flow

The vault list is driven end-to-end by a Room `Flow`:

```
CredentialDao.getAllCredentials(): Flow<List<CredentialEntity>>
   └─ decryptAll(...) -> VaultSnapshot          CredentialRepositoryImpl
        │                                        items + undecryptableIds + isLocked
        └─ map { it.toSummary() }
             └─ combine(query, sort, filter)     VaultViewModel
                  └─ stateIn(WhileSubscribed)    VaultUiState
```

Any write through the DAO re-emits the whole list, so `onTogglePin` re-sorts it. That no
longer disturbs the password-age signal: `updatedAt` and `passwordChangedAt` are separate
columns, and only a genuine password change advances the second (#29).

The second step used to be `mapNotNull` over a `catch { null }`, which made it the most
consequential line in the app — every decryption failure became invisible, and four
distinct data-loss bugs all surfaced as "you have no passwords" (#40). `VaultSnapshot`
carries the failures instead, and a locked vault is reported as locked rather than as a
pile of failures, so the error banner does not fire on every auto-lock.

## Dependency injection

All Hilt, all `SingletonComponent`:

| Module | Provides |
| --- | --- |
| `AppModule` | `CredentialRepository` ← `CredentialRepositoryImpl` |
| `DatabaseModule` | `VaultDatabase` (SQLCipher `SupportOpenHelperFactory`), `CredentialDao` |
| `FirebaseModule` | `FirebaseAuth`, `FirebaseFirestore` |
| `SecurityModule` | The two `SecurePrefs` stores, `BiometricKeystore`, `PwnedRangeSource` |
| `DispatcherModule` | `@CryptoDispatcher` |

Everything else in `security/` is `@Singleton` with `@Inject constructor` and needs no
module.

`DatabaseModule.provideDatabase` probes the database through `VaultDatabaseHealthCheck`
before handing out a handle, so an unopenable vault becomes a recovery screen rather than
an exception surfacing later inside a Room `Flow`. **It modifies nothing.** It used to
delete `vault.db` on any exception from that probe (#1); moving a database aside is now a
confirmed user action, and renames rather than deletes.

## Navigation

Single `NavHost` in `MainActivity`. Start destination is chosen once, from
`MasterPasswordManager` state:

```
!isSetupComplete   → setup
!isVaultUnlocked   → unlock
otherwise          → vault

vault → detail/{id} → add_edit?id={id}
      → add_edit
      → generator
      → settings
```

Lock transitions navigate with `popUpTo(0) { inclusive = true }` so no protected screen
survives on the back stack.

`recovery` is reachable only when the health check reports the database unopenable, and it
takes precedence over every other start destination — setup can look complete while the
vault itself cannot be read.

The `generator` route carries a `forResult` flag. Opened from the vault toolbar it is a
standalone tool; opened from Add/Edit it offers "Use this password" and hands the result
back through the previous back-stack entry's `SavedStateHandle`. It previously had no
result channel at all, and Add/Edit's `onGeneratePassword` callback was never invoked
(#33).

## Build

- AGP 9.0.1, Kotlin 2.3.20, KSP for Hilt and Room
- `minSdk` 28, `target`/`compile` 36, Java 11 source level
- Release: `isMinifyEnabled` + `isShrinkResources` on, debug-signed so a minified build can
  be installed and exercised locally. Verified against `usage.txt` and `mapping.txt`, and
  run on a device
- `google-services` plugin wires Firebase and is the only source of `default_web_client_id`
  (#42)
- Tests: ~310 host-side, plus instrumented Room migration tests
