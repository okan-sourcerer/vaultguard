# Architecture

Single-module Android app (`:app`), Kotlin, Jetpack Compose, Hilt, Room over SQLCipher,
optional Firestore sync. Loosely layered along Clean Architecture lines.

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
