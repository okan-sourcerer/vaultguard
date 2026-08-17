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
  decryption and matching. They do not go through `CredentialRepository`. This is why the
  matching logic is duplicated and has drifted (see [FINDINGS.md](FINDINGS.md) #11).

## Process entry points

| Entry point | Trigger | Notes |
| --- | --- | --- |
| `VaultGuardApp` | Process start | Loads the SQLCipher native lib, plants Timber in debug, registers auto-lock |
| `MainActivity` | Launcher icon | `FragmentActivity` (required by `BiometricPrompt`), sets `FLAG_SECURE`, hosts the whole Compose nav graph |
| `VaultAutofillService` | System autofill request | Runs in the same process; sees the same `MasterPasswordManager` singleton |
| `AutofillAuthActivity` | Autofill dataset auth | Unlocks the *global* vault as a side effect |
| `AutofillSaveActivity` | Autofill save request | Started with `FLAG_ACTIVITY_NEW_TASK` from the service |
| `ClearClipboardWorker` | WorkManager, +30 s | Survives process death |

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
`delay(timeoutMinutes)` coroutine on `onStop` and cancels it on `onStart`. Two consequences:
the timer only runs while the process is alive (process death locks the vault anyway, so
this is safe), and the timeout is currently held in memory only and resets to 5 minutes on
every launch (#25).

## Threading

| Work | Where it runs today | Where it should run |
| --- | --- | --- |
| Argon2id derivation | **Main thread** (`viewModelScope` default) | A `Dispatchers.Default` context |
| Payload encrypt/decrypt | Caller's thread — often Main via Room `Flow.map` | Off Main for bulk operations |
| Room suspend DAO calls | Room's own executor | unchanged |
| `getAllBlocking()` (autofill) | `Dispatchers.IO` in the service, **Main** in `onSaveRequest`'s `runBlocking` | Off Main everywhere |
| Firestore | Play Services callbacks, bridged with `.await()` | unchanged |
| Clipboard clear | WorkManager worker | unchanged |

The Main-thread Argon2 calls (#17) and the `runBlocking` in `onSaveRequest` (#18) are the
two real ANR risks.

## Reactive data flow

The vault list is driven end-to-end by a Room `Flow`:

```
CredentialDao.getAllCredentials(): Flow<List<CredentialEntity>>
   └─ map { decryptEntity(it) }                CredentialRepositoryImpl
        └─ mapNotNull { it.toSummary() }       drops undecryptable rows  ← #40
             └─ combine(query, sort, filter)   VaultViewModel
                  └─ stateIn(WhileSubscribed)  VaultUiState
```

Any write through the DAO re-emits the whole list. This is why `onTogglePin` — which
round-trips through `save()` and bumps `updatedAt` — both re-sorts the list and corrupts
the "password age" signal (#29).

`mapNotNull` at the second step is the single most consequential line in the app: it is
where every decryption failure becomes invisible.

## Dependency injection

All Hilt, all `SingletonComponent`:

| Module | Provides |
| --- | --- |
| `AppModule` | `CredentialRepository` ← `CredentialRepositoryImpl` |
| `DatabaseModule` | `VaultDatabase` (SQLCipher `SupportOpenHelperFactory`), `CredentialDao` |
| `FirebaseModule` | `FirebaseAuth`, `FirebaseFirestore` |

Everything in `security/` is `@Singleton` with `@Inject constructor` and needs no module.

`DatabaseModule.provideDatabase` currently contains a destructive recovery path (#1) —
it opens the DB as a probe and deletes it on any exception. That is the highest-priority
fix in the plan.

Note: `SecureClipboard` is annotated `@Singleton` but is constructed by hand in two
Compose screens (`remember { SecureClipboard(context) }`), bypassing Hilt (#46).

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

The `generator` route is reachable from the vault toolbar and from Add/Edit, but it has no
result channel — the Add/Edit screen's `onGeneratePassword` nav callback is never invoked,
and generating there cannot hand a password back (#33).

## Build

- AGP 9.0.1, Kotlin 2.3.20, KSP for Hilt and Room
- `minSdk` 28, `target`/`compile` 36, Java 11 source level
- Release: `isMinifyEnabled` + `isShrinkResources` on, with ProGuard rules that reference
  packages the app does not use (#41) — release builds are not currently verified
- `google-services` plugin wires Firebase; `strings.xml` also hand-declares
  `default_web_client_id`, which the plugin generates too (#42)
- Tests: two IDE templates, nothing real (#44)
