# Known defects

Catalogue from the full-project review of 2026-08-17, plus #47–#56 found later by using
the app rather than reading it. Numbering is stable — the remediation plan and commit
messages reference these IDs. Update **Status** as fixes land.

Status values: `open`, `in progress`, `fixed`, `won't fix`.

## Summary

| Group | State |
| --- | --- |
| P0 — vault destruction and data loss (#1–#8) | all fixed |
| P1 — security (#9–#18) | all fixed |
| P2 — sync (#19–#24) | all fixed, #22 obsolete |
| P3 — business logic and UX (#25–#40) | all fixed |
| P4 — build and hygiene (#41–#46) | all fixed except the Credential Manager migration |
| P1b — autofill usability (#47–#56) | all fixed |
| P5 — flow and interaction (#57–#63) | all fixed |
| P6 — found by the desktop client (#64) | all fixed |

**Still open**, all deliberate rather than forgotten:

| # | Item | Why it is still open |
| --- | --- | --- |
| 4 (part) | Joining an account that already holds a different vault | The data-loss halves are fixed and a mismatch now refuses rather than merging. Designing the join flow needs a second device |
| 45 (part) | `GoogleSignIn` → Credential Manager | A different auth flow with its own failure modes. Deserves its own change, not a line in a hygiene pass |

Everything else in the catalogue is fixed.

Verified on the owner's device against both the debug and the minified release build:
vault loads, biometric unlock, autofill in a third-party app, and clipboard clearing.

**Chunks 14 and 15 are not yet device-verified.** #50 in particular cannot be believed
until it is seen: the chip is drawn by the keyboard, in another process, from a Slice this
app builds, and R8 inlines most of the library that builds it. The chunk 15 work is
ordinary Compose and carries less platform risk, but the undo snackbar (#59) and the
discard guard (#60) are both about timing and back-navigation, which is exactly where a
device disagrees with a test.

## P0 — Vault destruction / silent data loss

| # | Defect | Location | Status |
| --- | --- | --- | --- |
| 1 | DI deletes the entire vault on any DB-open failure | `di/DatabaseModule.kt:35-50` | **fixed** (chunk 2) |
| 2 | `allowBackup=true` with template rules; restore triggers #1 | `AndroidManifest.xml:9-11`, `res/xml/*` | **fixed** (chunk 2) |
| 3 | Import yields undecryptable entries (double-encrypted backup) | `usecase/ImportVaultUseCase.kt:48-74` | **fixed** (chunk 7) |
| 4 | Google sign-in adopts remote salt, uploads then orphans local vault | `settings/SettingsViewModel.kt:219-247` | **mostly fixed** (chunk 10) |
| 5 | Master-password change is not transactional | `usecase/ChangeMasterPasswordUseCase.kt:27-42` | **fixed** (chunk 5) |
| 6 | Password change leaves biometric wrapping the old key | `usecase/ChangeMasterPasswordUseCase.kt` | **fixed** (chunk 4) |
| 7 | `unlockWithKey` never validates the key | `security/MasterPasswordManager.kt:96-98` | **fixed** (chunk 3) |
| 8 | Cancelling biometric enrolment permanently breaks biometric unlock | `security/BiometricAuthManager.kt:49-89` | **fixed** (chunk 4) |

**#1** — a catch-all `catch (_: Exception)` around a probe-open calls
`context.deleteDatabase("vault.db")`. The comment asserts this only happens on a fresh
install; transient I/O errors, SQLCipher version changes, and lost preferences all reach
it too.

**#2** — both backup XML files are unedited IDE templates, so everything is backed up
including `vault.db` and the Keystore-backed preferences. The Keystore master key is *not*
backed up, so on restore the preferences are unreadable, a new DB passphrase is generated,
the restored database fails to open, and #1 deletes it.

**#3** — export sealed an outer envelope under the session key but left each
`encryptedPayload` under the export-time master key; import re-inserted those bytes without
re-encrypting. Only round-tripped on the same device with an unchanged master password.

Fixed by format v2: a single encryption layer, a backup password independent of the master
password, KDF parameters carried in the file, and an importer that re-encrypts under the
receiving vault's key. Existing v1 files became restorable in the same change — their outer
envelope and inner payloads share one key, so the backup password opens both.

**#4** — `fullSync()` ran *before* `lockVault()`, pushing rows encrypted under the old
local key into the shared remote vault, and the adopted salt orphaned every local row.

The data-loss halves are fixed. The vault config now carries the wrapped vault key, so
adopting one leaves a vault that can actually be opened; and a salt mismatch during sync
turns sync off and reports it rather than uploading rows the destination could never read.

What remains is onboarding, not correctness: joining an account that already holds a
different vault is refused, with instructions, instead of offering to merge or replace.
That is a flow to design, and it needs a second device to exercise.

**#5** — salt and verification are switched before the re-encryption loop, which has no
transaction. A failure mid-sweep splits the vault across two keys.

**#6, #7** — nothing re-wraps or invalidates the biometric key on password change, and the
biometric path accepts a key without checking it against the verification blob. Result:
fingerprint unlock "succeeds" into an empty vault.

**#8** — `generateBiometricKey()` overwrites the Keystore key *before* the prompt. Cancel
the prompt and the stored wrapped key is orphaned, while `isBiometricEnabled` still
reports true.

## P1 — Security

| # | Defect | Location | Status |
| --- | --- | --- | --- |
| 9 | Autofill classifies email/URI/postal fields as passwords | `autofill/StructureParser.kt:96-107` | **fixed** (chunk 8) |
| 10 | Domain matching is suffix-based (`notgoogle.com` matches `google.com`) | `autofill/VaultAutofillService.kt:243-254` | **fixed** (chunk 8) |
| 11 | Locked-vault autofill uses looser matching and auto-fills first match | `autofill/AutofillAuthActivity.kt:161-194` | **fixed** (chunk 8) |
| 12 | Brute-force backoff caps at 32 s and resets on restart | `unlock/UnlockViewModel.kt:82-98` | **fixed** (chunk 9) |
| 13 | `FLAG_SECURE` missing on both autofill activities | `MainActivity.kt:35` only | **fixed** (chunk 8) |
| 14 | Breach check reports "not breached" on network failure | `security/BreachCheckService.kt:45-48` | **fixed** (chunk 13) |
| 15 | Vault uploads to Firebase without consent while UI says "Local only" | `vault/VaultViewModel.kt:139-150` | **fixed** (chunk 10) |
| 16 | Sign-out claims sync disabled, re-enables it anonymously | `security/GoogleAuthManager.kt:97-101` | **fixed** (chunk 10) |
| 17 | Argon2 runs on the main thread | `SetupViewModel.kt:70`, `UnlockViewModel.kt:55`, `AutofillAuthActivity.kt:128` | **fixed** (chunk 5) |
| 18 | `onSaveRequest` does `runBlocking` on the main thread | `autofill/VaultAutofillService.kt:151` | **fixed** (chunk 5) |

**#9** — `TYPE_TEXT_VARIATION_*` are values inside `TYPE_MASK_VARIATION`, not independent
bits, so `and … != 0` is wrong. An email field (`0x21`) `and TYPE_TEXT_VARIATION_WEB_PASSWORD`
(`0xe0`) is `0x20` ≠ 0 → classified as a password. Same for URI (`0x11`) and postal
address (`0x51`) against `VISIBLE_PASSWORD` (`0x90`). The fix is
`(inputType and TYPE_MASK_VARIATION) == …`.

Related, same file: the `autofillHints` branch compares `hint.lowercase()` against
`View.AUTOFILL_HINT_EMAIL_ADDRESS`, whose value is `"emailAddress"` — the branch can never
match. `AUTOFILL_HINT_PASSWORD` and `AUTOFILL_HINT_USERNAME` are already lowercase and do
match. The hint-text fallback also matches `"pass"` inside "passport" and "passenger".

**#11** — `credSite.contains(domain.split(".").first())` and `pkg.contains(siteNameWord)`.
A hostile app declaring package `com.evil.gmail` matches a credential named "Gmail", and
the code returns `credentials.first()` with no user selection.

**#12** — `(1 shl (attempts - 3).coerceAtMost(4)) * 2` maxed at 32 seconds, and
`failedAttempts` lived only in the ViewModel, so killing the app reset it. `AutofillAuthActivity`
had no rate limiting at all.

Replaced by `UnlockThrottle`: persisted in the encrypted preferences, escalating to an hour,
and applied inside `UnlockVaultUseCase` so every unlock path inherits it. It is a deterrent,
not a boundary — the lockout is wall-clock based, so anyone able to change the device clock
can shorten it. The real cost of a guess is Argon2id.

**#14** — the catch block's own comment said "report unknown", but `BreachResult` had no
unknown state, so it returned `isBreached = false` and the UI printed a green all-clear.
Non-200 responses behaved the same, because they were turned into an empty body.

`BreachCheckResult` now distinguishes Breached, Safe and **Unavailable**, and the screen
renders the third in neutral grey rather than green. The range fetch moved behind
`PwnedRangeSource` so the parsing and error mapping are testable without a network — which
also surfaced that padding entries were never filtered: `Add-Padding` mixes in decoy
suffixes carrying a count of zero, and matching one would have reported a password as
breached zero times.

## P1b — Autofill usability

Found while reviewing why autofill was unpleasant enough not to be used. These are not in
the original 2026-08-17 catalogue: that review looked for logic and security defects in the
code as written, and did not ask whether the feature functions end-to-end on a current
Android device. That question depends on platform constraints — background-activity-launch
rules, compatibility mode, inline suggestions — which the code gives no hint of.

None are data-loss or security issues. They are the difference between a feature that is
correct and one worth switching on.

| # | Defect | Location | Status |
| --- | --- | --- | --- |
| 47 | Save dialog launched with `startActivity` from a service; blocked on Android 10+ | `autofill/VaultAutofillService.kt` | **fixed** (chunk 8a) |
| 48 | No `<compatibility-package>` entries, so browsers fill unreliably or not at all | `res/xml/autofill_service_config.xml` | **fixed** (chunk 8a) |
| 49 | Dismissal key collapses to the browser package, silencing every site at once | `autofill/AutofillDismissedPrefs.kt` | **fixed** (chunk 8a) |
| 50 | No inline suggestions, so results never reach the keyboard strip | `autofill/VaultAutofillService.kt` | **fixed** (chunk 14) |
| 51 | `cancellationSignal` ignored; responses delivered after cancellation | `autofill/VaultAutofillService.kt:43` | **fixed** (chunk 14) |
| 52 | Shared `PendingIntent` request code cancelled concurrent auth intents | `autofill/VaultAutofillService.kt` | **fixed** (chunk 8a) |
| 53 | `settingsActivity` points at `MainActivity`, landing on the unlock screen | `res/xml/autofill_service_config.xml` | **fixed** (chunk 14) |
| 54 | Only the last fill context is read, so a two-step login saves a blank username | `autofill/VaultAutofillService.kt:131` | **fixed** (chunk 14) |
| 55 | Duplicate check compares the blank username, so #54 also creates a duplicate | `autofill/VaultAutofillService.kt:164-166` | **fixed** (chunk 14) |
| 56 | A password changed on the site is treated as a duplicate and never saved | `autofill/VaultAutofillService.kt`, `autofill/AutofillSaveActivity.kt` | **fixed** (chunk 15) |

**#47** — `SaveCallback.onSuccess(IntentSender)` exists precisely so the *system* launches
the dialog. Calling `startActivity` from a backgrounded service is blocked on Android 10
and above, so the save prompt was silently suppressed and saving from autofill appeared to
do nothing.

**#48** — most Android browsers do not implement the Autofill Framework's virtual-view API.
Without a per-package opt-in the service receives no structure, or one with no web domain.
That missing domain is also what triggered #49.

**#49** — the key was `webDomain ?: packageName`, so in a browser it became the browser's
own package. Tapping Skip once on a single website silenced the save prompt for everything
browsed thereafter. #34 added expiry to this without addressing the key.

**#54** — two-step logins, where the username is on one screen and the password on the
next, are the norm rather than the exception: Google, Microsoft, Amazon and most banks work
this way. Android accumulates the whole session and hands `onSaveRequest` *every* fill
context; `onFillRequest` likewise receives each one collected so far. Both read only
`fillContexts.lastOrNull()`, which on a two-step login is the password screen alone. The
username typed on the first screen is discarded, and the credential is offered for saving
with an empty username. `SaveInfo.FLAG_DELAY_SAVE` (API 28, and minSdk is 28) exists for
exactly this shape of flow.

**#55** — the consequence. `findMatchingCredentials(...).any { it.username == username }`
compares against the empty string #54 produced, so it never matches the entry the user
already has. Instead of staying quiet about a known credential, the service prompts, and
accepting leaves a blank-username duplicate beside the good one. The two are one fix.

**#56** — the duplicate rule matches on username alone, so rotating a password on a website
looks identical to signing in again: the service sees a credential it already holds and
says nothing. The vault keeps the old password indefinitely, with nothing to indicate it is
now wrong, and the next autofill types a password the site will reject. Fixing it means
`AutofillSaveActivity` gaining an update path — "update the saved password for this
account?" — rather than the create-only flow it has, which is why it is not folded into
#55.


## P5 — Flow and interaction

Found by asking, of each action the UI offers, whether it can be reversed the same way it
was made, whether the app tells the truth about what it just did, and whether a write
preserves what the screen doing the writing does not model. The 2026-08-17 review looked
for defects *within* a screen; these are defects in the path between screens, which is why
none of them showed up then.

The audit covered the vault list, detail, Add/Edit, generator, settings, unlock and
recovery screens. It started because the owner reported #57 after a code reading of the
same lines had concluded the opposite.

| # | Defect | Location | Status |
| --- | --- | --- | --- |
| 57 | The list can unpin but never pin; pinning needs the edit screen | `ui/screens/vault/VaultScreen.kt:398` | **fixed** (chunk 15) |
| 58 | Pinning changes the "Updated" date shown on the detail screen | `ui/screens/detail/CredentialDetailScreen.kt:316` | **fixed** (chunk 15) |
| 59 | Delete is a soft delete with no restore path and no undo | `ui/screens/detail/CredentialDetailScreen.kt:73` | **fixed** (chunk 15) |
| 60 | Add/Edit discards typed changes on Back with no warning | `ui/screens/addEdit/AddEditScreen.kt:79` | **fixed** (chunk 15) |
| 61 | Editing a credential silently drops its autofill links | `ui/screens/addEdit/AddEditViewModel.kt:168-180` | **fixed** (chunk 15) |
| 62 | Deleting a generator preset is one accidental tap, with no confirmation | `ui/screens/generator/PasswordGeneratorScreen.kt:146` | **fixed** (chunk 15) |
| 63 | Auto-lock timeout reads "1 minutes" | `ui/screens/settings/SettingsScreen.kt:248,256` | **fixed** (chunk 15) |

**#57** — the pin `IconButton` rendered only `if (credential.isPinned)`, so the affordance
existed in one direction. A swipe-right gesture could pin, but its only indication is a
background icon revealed by the swipe already being under way, so nothing announces it. The
practical route to pinning was: open the entry, edit, toggle, save. Reported by the owner
using the app, after a code reading of the same lines concluded the opposite — the toggle
call is there, the *condition around it* is the bug.

**#58** — the other half of #29. That finding separated "row last written" from "password
last changed" and pointed the password-age display at the new column, but the detail
screen's `Updated:` line still reads `updatedAt`, which every write moves. Pinning an entry
is a write, so it reports the credential as updated today when nothing about it changed.
`updatedAt` cannot simply stop moving — sync pushes a row when `updatedAt > syncedAt`, and
the pin state travels in the encrypted payload, so it genuinely must be pushed. The fix
belongs on the display side.

**#59** — `delete` sets `isDeleted` and keeps the row, so the data survives a mistaken tap,
but nothing in the UI can reach it afterwards and the confirmation dialog is the only
safety. A tombstone the user cannot open is indistinguishable from a hard delete, while
still costing the storage and the sync traffic of a real row.

**#60** — Back leaves Add/Edit immediately. Anything typed is gone with no prompt, on a
screen where the thing being typed is a password that may exist nowhere else yet.

**#61** — the worst of this group, because it destroys data and says nothing.
`AddEditViewModel.onSave` constructs a fresh `Credential` from the form fields, and the
form has no field for `linkedPackages` or `linkedDomains`, so both take their `emptyList()`
default and overwrite what was stored. Those two lists are how [CredentialMatcher](../app/src/main/java/com/vaultguard/app/autofill/CredentialMatcher.kt)
ranks a match — package is rank 0, domain rank 1, its two strongest signals — and only
`AutofillSaveActivity` ever sets them. So an entry captured by autofill works until the
first time it is edited for any reason, after which autofill quietly stops offering it for
the app it was captured from. Nothing in the UI reports the loss, and the entry still looks
complete. Editing must carry unmodelled fields forward rather than rebuilding the object
from the form.

**#62** — the preset delete button sits *inside* the dropdown row that selects the preset,
so the tap that chooses one and the tap that destroys one are millimetres apart, and the
destructive one has no confirmation and no undo.

**#63** — `"$minutes minutes"` and `"${uiState.autoLockTimeout} minutes"` both render the
one-minute case as "1 minutes".

## P2 — Sync correctness

| # | Defect | Location | Status |
| --- | --- | --- | --- |
| 19 | Changes made during a sync are lost permanently | `data/remote/FirebaseSyncService.kt:139-144` | **fixed** (chunk 10) |
| 20 | Clock skew silently drops remote changes | `data/remote/FirebaseSyncService.kt:105-110` | **fixed** (chunk 10) |
| 21 | Unbounded Firestore batch (500-op cap) | `data/remote/FirebaseSyncService.kt:92-97, 197-203` | **fixed** (chunk 10) |
| 22 | Anon→Google config migration skipped when no credentials exist | `data/remote/FirebaseSyncService.kt:195` | **obsolete** (chunk 10) |
| 23 | Last-write-wins with arbitrary tiebreak; conflicts silently discarded | `data/remote/FirebaseSyncService.kt:117-119` | **fixed** (chunk 10) |
| 24 | Tombstones never purged, locally or remotely | schema-wide | **fixed** (chunk 10) |
| 64 | Wrapped vault key never republished to an existing cloud config | `data/remote/FirebaseSyncService.kt:193` | **fixed** (chunk 16) |

**#64** — `fullSync` published the vault configuration only when the cloud held none.
A vault whose config was published *before* it was converted to the vault-key layout
therefore carried a salt and a verification blob and no wrapped vault key, and conversion
does not change the salt, so every subsequent sync found a config that existed and matched
and republished nothing. Short of a master-password change, the wrapped key had no route
to the cloud at all.

Invisible from the phone, which reads its own local copy. It surfaced the first time a
second client tried to open the vault: the desktop reader verified the master password and
could not reach a single credential — finding #4 arriving by a different road.

The decision now lives in `SyncMerge.configAction`, which has three outcomes rather than
two, and is tested off Firestore like the rest of the merge rules.

Full analysis in [SYNC.md](SYNC.md).

## P3 — Business logic and UX contradictions

| # | Defect | Location | Status |
| --- | --- | --- | --- |
| 25 | Auto-lock timeout never persisted; silently reverts to 5 min | `security/VaultAutoLock.kt:21` | **fixed** (chunk 11) |
| 26 | Two contradictory definitions of "weak password" | `settings/SettingsViewModel.kt:79-84` vs `util/PasswordStrengthEvaluator.kt` | **fixed** (chunk 11) |
| 27 | Strength evaluator has no dictionary check (`Password1!` → STRONG) | `util/PasswordStrengthEvaluator.kt` | **fixed** (chunk 11) |
| 28 | Setup and change-password enforce different rules | `SetupViewModel.kt:52-65` vs `SettingsScreen.kt:517-522` | **fixed** (chunk 11) |
| 29 | "Password age" actually means "last edited" | `detail/CredentialDetailScreen.kt:211-251` | **fixed** (chunk 6) |
| 30 | Duplicate-password count inflated; empty passwords grouped | `settings/SettingsViewModel.kt:86-89` | **fixed** (chunk 11) |
| 31 | Wrong password in autofill unlock gives zero feedback | `autofill/AutofillAuthActivity.kt:128-131` | **fixed** (chunk 3) |
| 32 | Biometric failure/cancel is silent | `unlock/UnlockViewModel.kt:73-80` | **fixed** (chunk 3) |
| 33 | Generator cannot return a password to Add/Edit | `NavGraph.kt:80`, `AddEditScreen.kt:50` | **fixed** (chunk 11) |
| 34 | Autofill "Skip" is permanent and irreversible | `autofill/AutofillDismissedPrefs.kt` | **fixed** (chunk 8) |
| 35 | Save activity discards the credential if the vault is locked | `autofill/AutofillSaveActivity.kt:74-77` | **fixed** (chunk 8) |
| 36 | Clipboard worker wipes whatever was copied since | `security/SecureClipboard.kt` | **fixed** (chunk 11, corrected in 13a) |
| 37 | Autofill toggle in Settings cannot turn autofill off | `settings/SettingsScreen.kt:288-306` | **fixed** (chunk 8a) |
| 38 | Blank detail screen / infinite spinner on missing credential | `CredentialDetailScreen.kt:113`, `AddEditViewModel.kt:73` | **fixed** (chunk 3) |
| 39 | No "forgetting this loses everything" warning at setup | `setup/SetupScreen.kt` | **fixed** (chunk 11) |
| 40 | A locked or unreadable vault renders as an empty vault | `data/repository/CredentialRepositoryImpl.kt:83-96` | **fixed** (chunk 3) |

**#40 is the force multiplier.** `decryptEntity` swallows every exception into `null` and
`mapNotNull` drops the row. This single catch is why #3, #4, #6, and #7 all present to the
user as "No credentials yet. Tap + to add one." Fixing it early makes every subsequent fix
verifiable.

**#26** — Vault Stats counted a 40-character generated passphrase as weak for lacking an
uppercase letter, while the entropy evaluator called the same string VERY_STRONG. Stats now
call the evaluator; there is one definition.

**#27** — the evaluator now checks a list of commonly guessed passwords before doing any
arithmetic, matching against a normalised form so `P@ssw0rd123!` reduces to `password`. It
also refuses to call anything strong that uses three or fewer distinct characters, however
long — `aaaaaaaaaaaaaaaa` computed to 75 bits.

**#29** — `updatedAt` is bumped by editing notes, toggling a pin, or the master-password
re-encryption sweep. Pinning an entry resets its displayed "password age" to *Today*.

## P4 — Build, configuration, hygiene

| # | Defect | Location | Status |
| --- | --- | --- | --- |
| 41 | ProGuard keeps reference packages the app does not use | `app/proguard-rules.pro` | **fixed** (chunk 12) |
| 42 | `default_web_client_id` hand-declared *and* plugin-generated | `res/values/strings.xml:3` | **fixed** (chunk 12) |
| 43 | `google-services.json` not gitignored | `.gitignore` | **fixed** (chunk 0) |
| 44 | No real tests — only the two IDE templates | `app/src/test`, `app/src/androidTest` | **fixed** (chunks 1–12) |
| 45 | Deprecated `GoogleSignIn` API; `biometric` on an alpha version | `GoogleAuthManager.kt`, `libs.versions.toml:22` | **partly** (chunk 12) |
| 46 | Assorted hygiene (see below) | various | **fixed** (chunk 12) |

**#41** — `-keep class net.sqlcipher.**` matched nothing: that is the package of the
*older* SQLCipher artifact, while this app uses `net.zetetic.database`, whose classes are
resolved by name from native code. `-keep class org.signal.argon2.**` referenced a library
never present; BouncyCastle's Argon2 is constructed directly and needs no rule.

Fixing it turned up a keep rule that was missing rather than wrong: **WorkManager
instantiates workers reflectively from a stored class name**, and nothing refers to
`ClearClipboardWorker` by type, so R8 was free to remove it — the clipboard would simply
never have been cleared in a release build. Verified against `usage.txt` and `mapping.txt`
from a real minified build, which now also installs, since release is debug-signed for
local testing.

**#42** — the plugin generates `default_web_client_id` from `google-services.json`, and
`strings.xml` declared it too. It worked only because both copies held the same value:
`app/src/main/res` overrides generated resources, so the hand-written one silently won.
Swapping in a different Firebase project would have kept the stale value and broken
sign-in with an error pointing nowhere near the cause. The hand-written entry is gone.

**#45** — `biometric` moved from `1.2.0-alpha05` to stable `1.1.0`; every API the app uses
exists there, and an alpha is a poor dependency for an authentication path. The
`GoogleSignIn` migration to Credential Manager is **not** done: it is a different auth
flow with its own dependency and failure modes, and it is working. Worth doing, but as its
own piece of work rather than folded into a hygiene pass.

**#42** — verified 2026-08-17 by building and inspecting the merged resources. The string
is declared in **both** `res/values/strings.xml` and the plugin-generated
`build/generated/res/processDebugGoogleServices/values/values.xml`, currently with
identical values. AGP gives the main source set priority over generated resources, so the
build succeeds and behaviour is correct today. The hazard is latent rather than immediate:
if the Firebase project is ever changed, `google-services.json` updates but the
hand-written string silently keeps winning with a stale client ID, producing a sign-in
failure with no obvious cause. Fix is to delete the line from `strings.xml` and let the
plugin own it.

**#46** — `SecureClipboard` is `@Singleton` but hand-constructed in two Compose screens;
`ClipboardManager.kt` contains no class of that name; `ExportVaultUseCase` injects an
unused `KeyDerivation`; `CredentialRepository.search()` is dead code;
`CircularProgressIndicator()` has no size modifier inside buttons in Setup and Add/Edit;
Timber logging runs unguarded on every Settings recomposition.

### Regression: the #36 fix stopped the clipboard clearing at all

The first attempt tagged the clip with a token and skipped clearing when it did not match.
It always failed to match: Android 10's clipboard restriction covers
`getPrimaryClipDescription()` too, not only `getPrimaryClip()`, so a background worker
reads `null` and concluded every time that the clipboard belonged to someone else.

Passwords stayed on the clipboard indefinitely — a worse failure than the one being fixed,
and one that only shows up by using the app, since nothing in the code says the read is
restricted.

Corrected by inverting the default: clear unless the clip is *positively* identified as
another app's.

That exposed a second problem — nothing was firing the clear on time, or at all. An
in-process timer stops when Android freezes the process, which since Android 12 happens
within seconds of the app being backgrounded. A WorkManager job with an initial delay goes
through JobScheduler, which batches deferred work into maintenance windows. Both were
tried; neither honours a thirty-second window while the user is in another app, which is
the only moment that matters for a clipboard.

Now handled by a `shortService` foreground service, which is exempt from freezing and from
background execution limits, with WorkManager left on at double the delay as a long-stop.
The notification it must post is itself useful: it says a password is on the clipboard and
offers to clear it early.

## Documentation defects (fixed 2026-08-17)

The project had no documentation of any kind. Additionally, several code comments asserted
things the code did not do — these are load-bearing because #1 was justified by one of them.

| Comment | Problem | Status |
| --- | --- | --- |
| `DatabaseModule.kt:31-34` "safe because a passphrase mismatch only happens on a fresh install" | False premise behind #1 | **corrected** (chunk 2) |
| `MasterPasswordManager.kt:35` "Resets on collect" | Resets on explicit `consumeLockEvent()` | **corrected** (chunk 3) |
| `CredentialSummary.kt:4-6` "password … never sits in memory while browsing" | True for the list, false for the detail screen | **corrected** (chunk 3) |
| `BreachCheckService.kt:46` "just report unknown" | The type has no unknown state — #14 | to correct with #14 |
| `FirebaseSyncService.kt:53-60` | Two stacked KDoc blocks; the first describes an obsolete Firestore path | to correct |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Unedited templates with real security consequences — #2 | **corrected** (chunk 2) |

User-facing copy that contradicts behaviour is tracked as #14, #15, #16, and #3.

## Not defects

Checked and cleared during review, recorded so they are not re-investigated:

- **Generator bounds.** Slider range is 4–64 and there are at most 4 character pools, so
  the "guarantee one char per pool" loop cannot write out of bounds.
- **Pinned-entry sort stability.** `sortedByDescending { it.isPinned }` is a stable sort,
  so ordering within each group is preserved as the comment claims.
- **HIBP k-anonymity.** Correctly implemented; only a 5-character SHA-1 prefix is sent.
- **`ChangeMasterPasswordUseCase` old-key capture.** `oldKey` is read before
  `updateMasterPassword` swaps the session key; the reference stays valid.
- **Import "replace" mode tombstones.** Soft-deleting everything then upserting imported
  rows correctly revives rows that reappear in the backup.
