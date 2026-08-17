# VaultGuard

An offline-first Android password manager. Credentials are encrypted on-device with a
key derived from a master password that never leaves the device and is never stored.
Optional Google-account sync replicates only encrypted blobs.

**Status: pre-1.0, single-install.** Not published. Not audited.

A full review in August 2026 found 53 defects, including eight that could destroy or
orphan a vault. All of those are fixed, along with every security and sync finding; the
remainder are catalogued in [docs/FINDINGS.md](docs/FINDINGS.md) with what is left still
open. The debug and minified release builds have both been exercised on a device.

Keep your own backup regardless. Nothing here has been audited by anyone but its authors,
and a forgotten master password is unrecoverable by design.

## What it does

- **Vault** — encrypted credential store (site, app, URL, username, password, notes,
  category, tags, pinning) with search, sort, and category filters.
- **Master password** — Argon2id-derived key, verified against a sealed test blob.
  There is no recovery path. Forgetting it means losing the vault.
- **Biometric unlock** — the vault key is wrapped by an Android Keystore key gated on
  a strong biometric.
- **Autofill** — an `AutofillService` that fills and offers to save credentials in other
  apps and browsers.
- **Password generator** — configurable character pools with saved presets.
- **Breach check** — HaveIBeenPwned range API using k-anonymity (only a 5-character
  SHA-1 prefix leaves the device).
- **Backup** — encrypted JSON export/import.
- **Cloud sync** — optional Firestore replication of encrypted blobs, keyed to a Google
  account.

## Build and run

Requires Android Studio (AGP 9.0.1 / Kotlin 2.3.20) and a device or emulator on API 28+.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:installDebug
```

Unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Minified release build. It is signed with the debug key so it can be installed locally —
replace that with a real keystore before distributing anything:

```bash
./gradlew :app:installRelease
```

`app/google-services.json` is **not** tracked — it holds the project's API key and OAuth
client IDs. A copy is on disk pointing at the `passwords-6e369` Firebase project; a fresh
clone needs one before Firebase will build. Replacing it is enough to point at a different
project: the google-services plugin derives `default_web_client_id` from it, and nothing
else hard-codes that value.

## Layout

```
app/src/main/java/com/vaultguard/app/
  autofill/    AutofillService, structure parsing, auth + save activities
  data/        Room + SQLCipher storage, Firestore sync, repositories
  di/          Hilt modules
  domain/      Models, repository interfaces, use cases
  security/    Crypto, key derivation, Keystore, biometrics, auto-lock, clipboard
  ui/          Compose screens, view models, navigation, theme
  util/        Password strength evaluation
```

## Documentation

| Document | Covers |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, dependency flow, lifecycle, threading |
| [docs/SECURITY.md](docs/SECURITY.md) | Key hierarchy, crypto primitives, threat model, frozen invariants |
| [docs/DATA-FORMATS.md](docs/DATA-FORMATS.md) | DB schema, payload JSON, backup file format, Firestore layout |
| [docs/SYNC.md](docs/SYNC.md) | Current sync design, why it loses data, target design |
| [docs/FINDINGS.md](docs/FINDINGS.md) | Catalogue of known defects |
| [docs/REMEDIATION-PLAN.md](docs/REMEDIATION-PLAN.md) | Ordered, test-first fix plan |

Read [docs/SECURITY.md](docs/SECURITY.md) before changing anything under `security/`.
It records which values are frozen because live vaults depend on them.
