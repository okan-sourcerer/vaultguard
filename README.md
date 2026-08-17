# VaultGuard

An offline-first Android password manager. Credentials are encrypted on-device with a
key derived from a master password that never leaves the device and is never stored.
Optional Google-account sync replicates only encrypted blobs.

**Status: pre-1.0, single-install.** Not published. Not audited. See
[docs/FINDINGS.md](docs/FINDINGS.md) for known defects — several are data-loss bugs that
are actively being fixed. Do not install this on a device whose vault you cannot afford
to lose until the P0 items in [docs/REMEDIATION-PLAN.md](docs/REMEDIATION-PLAN.md) are done.

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

`app/google-services.json` is committed and points at the `passwords-6e369` Firebase
project. Cloud sync will not work against a different Firebase project without replacing
that file and the `default_web_client_id` string in `app/src/main/res/values/strings.xml`.

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
