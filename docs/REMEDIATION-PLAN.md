# Remediation plan

Ordered, test-first plan for the defects in [FINDINGS.md](FINDINGS.md).

## Operating constraints

These come from the project's actual situation and shape every decision below.

1. **There is a live vault with real passwords on one device.** Every change is evaluated
   for "can this corrupt or orphan the existing vault?" before it is evaluated for
   elegance.
2. **Single install.** No other devices, no released versions. Migrations can assume one
   known starting state and do not need version negotiation.
3. **The device is the source of truth; Firestore is disposable.** The remote vault may be
   wiped and rebuilt from the device. This removes the need for any remote data migration.
4. **Frozen crypto.** The Argon2id parameters and the UTF-16BE password encoding in
   [SECURITY.md](SECURITY.md) cannot change without re-deriving the whole vault. Chunk 1
   pins them with golden-vector tests before anything else moves.

## Ordering rationale

- **Chunk 2 first** because #1 and #2 are an armed destruction path. Nothing else matters
  if the vault gets deleted mid-project.
- **Chunk 3 early** because #40 (silent decrypt failure) is what hides the damage from
  every other bug. Once failures are visible, subsequent chunks become self-verifying —
  a regression shows up as an error instead of an empty list.
- **Chunk 7 (working export) before chunk 10 (sync rework)** so there is a real backup
  path in place before touching the riskiest subsystem.
- **Chunk 12 last** because ProGuard verification requires a release build, which is only
  meaningful once the code has stopped moving.

## Test strategy

The app has no tests (#44). Rather than chase coverage, tests are written where they buy
one of two things: **pinning behaviour that must not change** (crypto), or **exercising
pure logic that is currently wrong** (autofill matching, sync merge, strength scoring).

**Pure JVM tests** — no Android framework needed. `CryptoManager`, `KeyDerivation`,
`GeneratePasswordUseCase`, `PasswordStrengthEvaluator`, `StructureParser`'s classification
logic once extracted, and the sync merge rule once extracted. `javax.crypto` and
BouncyCastle both work on the host JVM.

**Robolectric tests** — for `android.util.Base64`, `org.json`, and `Context`. Note that
`org.json` on a bare JVM unit test throws "not mocked"; either Robolectric or a
`testImplementation("org.json:json")` dependency is required.

**Instrumented tests** — only for the Room migration (chunk 6) and anything touching the
real Keystore. `androidx.room:room-testing` provides `MigrationTestHelper`.

**Testability refactors are in scope.** `MasterPasswordManager` currently constructs
`EncryptedSharedPreferences` inline, which makes it untestable off-device. Extracting a
narrow `SecurePrefs` interface with a real and a fake implementation is part of chunk 1,
not a separate cleanup.

Each chunk is a commit or a short series. A chunk is done when its tests pass, the app
builds, and the vault on the device still opens and shows every credential.

---

## Chunk 0 — Prerequisites

**Not code.** Do these before anything else.

- [ ] **`git init` + baseline commit.** The project is not under version control. There is
      currently no way to roll back a bad change to an app holding real passwords. This is
      the single highest-value five minutes in the plan.
- [ ] Add `app/google-services.json` to `.gitignore` before the first commit (#43), and
      confirm nothing else sensitive is tracked.
- [ ] **Export the vault from Settings.** Format v1 round-trips correctly on the same
      device as long as the master password does not change — a valid safety net for this
      work, and the only one available until chunk 7.
- [ ] Copy the exported file off the device.
- [ ] Record the current vault entry count. It is the regression check for every chunk.

**Done when:** a clean baseline commit exists and a backup file is stored off-device.

---

## Chunk 1 — Characterization tests

**Goal:** freeze current crypto behaviour so later chunks cannot silently break the live
vault. No production behaviour changes.

**Findings:** groundwork for all; closes part of #44.

**Test infrastructure:**
- Add `kotlinx-coroutines-test`, `mockk`, `robolectric`, `androidx.test:core`,
  `org.json:json`, and `androidx.room:room-testing` to the version catalogue.
- Configure `testOptions { unitTests.isIncludeAndroidResources = true }`.

**Tests:**
- `KeyDerivationTest` — **golden vector.** Fixed password + fixed 16-byte salt → assert the
  exact 32 derived bytes, hard-coded. This is the test that makes the UTF-16BE encoding
  and the Argon2 parameters tamper-evident. Generate the expected value from the current
  implementation, and document in a comment that changing it invalidates existing vaults.
- `KeyDerivationTest` — the input `CharArray` is zeroed after derivation; different salts
  give different keys; `generateSalt()` returns 16 bytes and does not repeat.
- `CryptoManagerTest` — round-trip; a fresh IV per call; decryption fails with a wrong key;
  decryption fails on a flipped ciphertext bit and on a flipped tag bit (GCM integrity);
  the `EncryptedData` `equals`/`hashCode` contract holds.
- `CredentialPayloadTest` — payload JSON round-trip including empty lists, unicode,
  embedded quotes; unknown fields are ignored; missing fields fall back to defaults.
- `BackupV1FormatTest` — pin the current v1 envelope shape end-to-end, including the
  same-key restore that works today. Chunk 7 must keep this green.

**Refactor:** extract `SecurePrefs` from `MasterPasswordManager` so it can be faked.

**Migration risk:** none — tests only.

**Done when:** tests pass on a clean checkout and the golden vector is committed.

---

## Chunk 1a — Cleartext migration export (temporary)

**Goal:** get the vault out of the app and into another password manager before the risky
chunks begin. A v1 encrypted backup is not sufficient — it cannot be restored once the
salt changes, which is the exact scenario a backup exists for.

**Findings:** none directly; a stopgap for #3 until chunk 7 lands.

**Delivered:**
- `domain/usecase/migration/CleartextCsv.kt` — RFC 4180 reader/writer in Bitwarden's CSV
  column layout, which KeePassXC, 1Password and Proton Pass can also import.
- `domain/usecase/migration/ExportCleartextVaultUseCase.kt` — runs off the main thread and
  **refuses to write an empty file**, so a decryption failure cannot masquerade as a
  successful backup.
- Settings → "Migration (temporary)" → Export Unencrypted CSV, behind a confirmation
  dialog that spells out the exposure.
- `CleartextCsvTest` — 18 tests, concentrated on escaping. Passwords containing commas,
  quotes, and newlines round-trip; a note with embedded newlines does not split a record.

**Removal:** delete the `migration` package, the `SettingsViewModel.onExportCleartext`
method, the Settings section, and `CleartextCsvTest`. Every touch point is marked
`TEMPORARY — CLEARTEXT MIGRATION AID`, so `git grep "CLEARTEXT MIGRATION"` finds all of
them. Do this once chunk 7 lands.

---

## Chunk 2 — Stop the vault from being destroyed

**Goal:** remove the armed data-destruction path.

**Findings:** #1, #2. Comment corrections for `DatabaseModule`, `backup_rules.xml`,
`data_extraction_rules.xml`.

**Tests first:**
- Given an existing `vault.db` that cannot be opened with the stored passphrase, assert the
  file **still exists** afterwards and a typed exception surfaces.
- Assert the quarantine rename happens and does not overwrite a previous quarantine file.

**Changes:**
- Delete the `context.deleteDatabase("vault.db")` call. Replace with: rename to
  `vault.db.quarantine-<timestamp>` and throw a typed `VaultUnreadableException`.
- Add a recovery screen that explains what happened, points at the quarantined file, and
  offers import — rather than crashing on a Hilt provision failure.
- Rewrite `backup_rules.xml` and `data_extraction_rules.xml` to exclude `vault.db`,
  `vault_secure_prefs`, `biometric_prefs`, and `sync_prefs`, keeping `allowBackup="true"`
  for the harmless remainder.
- Replace the false comment in `DatabaseModule` with an accurate one.

**Migration risk:** none — this only removes a destructive branch.

**Done when:** tests pass, the app opens the live vault normally, and a deliberately
corrupted copy produces the recovery screen instead of a wipe.

---

## Chunk 3 — Make failures visible

**Goal:** stop reporting broken decryption as an empty vault. This is the validation
harness for every later chunk.

**Findings:** #40, #7, #38, #31, #32.

**Tests first:**
- Repository surfaces a decrypt-failure count instead of dropping rows.
- `unlockWithKey` rejects a key that does not decrypt the verification blob.
- Detail and Add/Edit produce a not-found state for a missing id, not a blank screen or a
  permanent spinner.

**Changes:**
- `CredentialRepositoryImpl` — distinguish "no rows" from "N rows failed to decrypt";
  surface the latter through the UI state.
- `VaultScreen` — a distinct error state: *"N entries could not be decrypted"*, never the
  cheerful empty-state copy.
- `MasterPasswordManager.unlockWithKey` — verify against the verification blob, return a
  boolean, and reject on failure.
- `UnlockViewModel.onBiometricUnlock` — surface failure and cancellation.
- `AutofillAuthActivity` — wire up the already-rendered-but-never-assigned `error` state.
- Correct the `CredentialSummary` comment about passwords not being held in memory.

**Migration risk:** none, but this chunk may *reveal* pre-existing damage in the live
vault. That is the point. If it surfaces undecryptable rows, stop and investigate before
continuing.

**Done when:** all entries still decrypt, and deliberately corrupting one row produces a
visible error.

---

## Chunk 4 — Biometric key lifecycle

**Findings:** #6, #8.

**Tests first:** against a faked keystore wrapper — changing the master password
invalidates the stored wrapped key; a cancelled enrolment leaves prior biometric state
untouched.

**Changes:**
- Generate the new Keystore key only *after* successful authentication, or snapshot and
  restore the previous wrapped key on failure.
- `ChangeMasterPasswordUseCase` calls `disableBiometric()` on success, and the UI tells the
  user to re-enrol.
- `isBiometricEnabled` verifies that the wrapped key actually unwraps, not just that a
  value is present.

**Migration risk:** low. Worst case the user re-enrols a fingerprint.

---

## Chunk 5 — Atomicity and threading

**Findings:** #5, #17, #18.

**Tests first:**
- Injected failure partway through the re-encryption sweep leaves the vault fully readable
  with the **old** password — salt and verification unchanged.
- Argon2 is not invoked on the main dispatcher (assert via an injected dispatcher).

**Changes:**
- Restructure `ChangeMasterPasswordUseCase`: re-encrypt every row in memory first, then
  commit rows *and* the new salt/verification inside a single Room `@Transaction`. Roll
  back to the old key on any failure.
- Inject a `CoroutineDispatcher` into the managers; run Argon2 on `Dispatchers.Default`.
- Replace the `runBlocking` in `onSaveRequest` with the async `SaveCallback` pattern.

**Migration risk:** **highest in the plan** — this rewrites every row. Do not start without
a verified backup, and re-export immediately afterwards.

---

## Chunk 6 — Schema migration 1 → 2

**Goal:** separate "row last written" from "password last changed".

**Findings:** #29, groundwork for chunk 7.

**Tests first:** `MigrationTestHelper` instrumented test — a v1 database with rows migrates
to v2 with `passwordChangedAt` backfilled from `updatedAt`, and no rows lost.

**Changes:**
- Add `passwordChangedAt: Long` to `CredentialEntity`; bump the database to version 2.
- Write `MIGRATION_1_2`: `ALTER TABLE credentials ADD COLUMN passwordChangedAt INTEGER NOT NULL DEFAULT 0`,
  then `UPDATE credentials SET passwordChangedAt = updatedAt`.
- Enable `exportSchema = true` and commit the generated schema JSON.
- Set `passwordChangedAt` only when the password field actually differs on save.
- Point the detail screen's age display and the Vault Stats "older than 90 days" count at
  the new column.

**Migration risk:** moderate. Room's destructive fallback must stay **off** so a bad
migration fails loudly rather than wiping. Verify on a copy of the live database first.

---

## Chunk 7 — Backup format v2

**Findings:** #3.

**Tests first:**
- v2 round-trips across a *different* master password and salt — the case v1 cannot do.
- v1 backups still import on the same-key path (`BackupV1FormatTest` from chunk 1 stays green).
- A wrong backup password produces a clear error, not a silently empty import.
- Merge and replace modes behave as documented.

**Changes:**
- Implement the v2 envelope specified in [DATA-FORMATS.md](DATA-FORMATS.md): a single
  encryption layer, KDF parameters carried in the file, and a backup password independent
  of the master password.
- Export decrypts payloads and re-seals them under the backup key.
- Import decrypts, then **re-encrypts each credential under the local session key** — the
  actual fix — and reports a per-entry success count.
- Keep the v1 import path, read-only, dispatched on the `version` field.
- Correct the import dialog copy to match what each format can actually do.

**Migration risk:** low for the live vault (import is additive), high value — this produces
the first trustworthy backup.

---

## Chunk 8 — Autofill security

**Findings:** #9, #10, #11, #13, #34, #35.

**Tests first** — this is where unit tests pay off most, because the logic is pure:
- `StructureParser.classifyField` — table-driven over real `inputType` values: password,
  visible password, web password, email, web email, URI, postal address, person name.
  Each must classify correctly. These tests fail against the current code.
- `autofillHints` casing, including `"emailAddress"`.
- Hint-text matching must not fire on "passport" or "passenger".
- Domain matcher — `notgoogle.com` must not match `google.com`; `accounts.google.com`
  should match `google.com`; case and trailing-slash handling.
- Package matcher — `com.evil.gmail` must not match a credential named "Gmail".

**Changes:**
- Fix the variation mask: `(inputType and TYPE_MASK_VARIATION) == …`.
- Fix hint casing; tighten the hint-text keyword list to word boundaries.
- Extract one shared matcher and use it from both `VaultAutofillService` and
  `AutofillAuthActivity`, with dot-boundary domain comparison.
- `AutofillAuthActivity` returns a dataset **list** for the user to choose from, never
  auto-fills the first match.
- Add `FLAG_SECURE` to both autofill activities.
- Make "Skip" scoped and revocable, with a reset in Settings.
- Queue the captured credential when the vault is locked instead of discarding it.

**Migration risk:** none — no stored format changes.

---

## Chunk 9 — Authentication hardening

**Findings:** #12.

**Tests first:** backoff escalates without a low cap; attempt state survives a process
restart; the autofill unlock path is rate-limited by the same mechanism.

**Changes:** persist failed-attempt count and lockout expiry in `vault_secure_prefs`; move
the policy into `MasterPasswordManager` so both unlock paths share it; escalate meaningfully
past 32 seconds.

---

## Chunk 10 — Sync rework

**Findings:** #19, #20, #21, #22, #23, #24, #4, #15, #16.

The largest chunk. Full target design in [SYNC.md](SYNC.md). Because the cloud is
disposable, the rollout is: land the code, wipe `vaults/{uid}`, reset cursors, re-push once
from the device.

**Tests first:** extract the merge rule into a pure function and test it directly — a row
written during a sync is not skipped; a remote row with a skewed clock is not dropped;
conflicts are detected rather than silently resolved; batching splits above 450 operations.

**Changes:** monotonic revision cursor for push, Firestore server timestamps for pull,
chunked batches, explicit sync consent, a real "disable sync and delete cloud data" action,
truthful Settings copy, corrected adopt-remote-vault sequencing, tombstone purge.

**Migration risk:** low for local data, by construction — remote is rebuilt from local.

---

## Chunk 11 — Business logic and UX

**Findings:** #25, #26, #27, #28, #30, #33, #36, #37, #39.

**Tests first:** strength evaluator against a table of known-weak passwords (`Password1!`
must not score STRONG); duplicate counting; the clipboard guard.

**Changes:** persist the auto-lock timeout; delete the ad-hoc weak-password heuristic in
Vault Stats and use the shared evaluator; add a common-password list to the evaluator;
unify setup and change-password validation; fix duplicate counting and exclude empty
passwords; wire the generator's result back to Add/Edit; make the clipboard worker verify
the clip before clearing; make the autofill toggle honour its off state; add the
no-recovery warning at setup.

---

## Chunk 12 — Build and hygiene

**Findings:** #41, #42, #43, #44, #45, #46.

**Changes:** correct the ProGuard keeps to `net.zetetic.**` and add BouncyCastle rules;
**build and smoke-test a release APK** — this is the only way to catch #41; resolve the
`default_web_client_id` duplication; migrate off deprecated `GoogleSignIn` to Credential
Manager; move `biometric` off the alpha if a stable release exists; inject `SecureClipboard`
properly; rename `ClipboardManager.kt`; delete dead code.

**Done when:** a minified release build installs, unlocks the live vault, and autofills.

---

## Progress

| Chunk | Findings | Status |
| --- | --- | --- |
| 0 — Prerequisites | #43 | **done** — repo initialised, baseline commit, `google-services.json` untracked |
| 1 — Characterization tests | #44 (partial) | **done** — 57 tests passing |
| 1a — Cleartext migration export | temporary aid for #3 | **done** — awaiting the owner's backup |
| 2 — Stop destruction | #1, #2 | **done** — 22 tests |
| 3 — Visibility | #40, #7, #38, #31, #32 | **done** — 35 tests |
| 4 — Biometric lifecycle | #6, #8 | **done** — 24 tests |
| 5 — Atomicity + threading | #5, #17, #18 | **done** — 20 tests |
| 6 — Schema 1→2 | #29 | **done** — 6 unit + 4 migration tests |
| 6a — Schema 2→3 | #29 correction | **done** — the v2 backfill's source had been clobbered |
| 6.5 — Vault-key indirection | structural fix behind #5, #6 | **done** — 164 tests |
| 7 — Backup v2 | #3 | not started |
| 8 — Autofill security | #9, #10, #11, #13, #34, #35 | not started |
| 9 — Auth hardening | #12 | not started |
| 10 — Sync rework | #19–#24, #4, #15, #16 | not started |
| 11 — Business logic + UX | #25–#28, #30, #33, #36, #37, #39 | not started |
| 12 — Build + hygiene | #41–#46 | not started |
