# Cloud sync

Optional replication of encrypted credential blobs to Firestore, keyed to a Firebase Auth
user. Describes what the code does now, why it loses data, and the target design.

**Operating principle going forward: the device is the source of truth; the cloud is a
rebuildable replica.** The vault can always be reconstructed from the device, so no sync
fix needs a remote data migration — wiping and re-pushing is an acceptable recovery.
This assumption is what keeps the sync rework tractable.

## Design

### Consent

Sync is **off by default** and only ever turned on by an explicit action in Settings.
Signing in with Google does not enable it — uploading a vault is not something to infer
from a sign-in. Every entry point requires both `syncPreferences.isEnabled` and a signed-in
user; nothing signs in implicitly.

Turning it off can also delete the account's cloud vault. Signing out turns sync off and
stays signed out.

Previously any sync call would `signInAnonymously()` and upload everything, so pulling to
refresh the vault list created a cloud vault the owner never asked for, while Settings
displayed "Local only" (#15). Sign-out claimed to disable sync and immediately signed back
in anonymously (#16).

### Push: a per-row dirty flag

A row needs pushing when `syncedAt IS NULL OR updatedAt > syncedAt`. After a successful
upload, `syncedAt` is set to **the `updatedAt` that was uploaded** — not to "now" — so a row
edited while the batch was in flight still has `updatedAt > syncedAt` and stays pending.

This replaces a global "modified since" cursor stamped after the network round-trip, which
lost any write made during a sync (#19). `updatedAt` is also forced monotonic per row on
save, so a backwards clock cannot make an edited row look already-synced.

### Pull: a server timestamp

Every uploaded document carries `serverUpdatedAt`, set by `FieldValue.serverTimestamp()`.
Pull queries rows above the stored cursor ordered by that field, and the cursor advances to
the highest value **after** the rows are committed, so a crash re-pulls rather than skips.

Firestore assigns the value, so devices that disagree about the time still agree about the
order. Comparing device clocks against each other silently dropped changes (#20).

### Conflicts

Both sides changed since the last sync means the remote copy is written **alongside** the
local one under a fresh id, and the count is reported. Duplication is recoverable; a
silently discarded password is not. Last-write-wins used to pick a winner by comparing
device clocks and drop the other without a word (#23).

Deletes are the exception: a tombstone from either side wins over an edit on the other,
because deleting is unambiguous where an edit is not, and the entry is still in a backup.

The decision table lives in `SyncMerge`, separate from Firestore, so the rules are testable.

### Batching

Commits are chunked at 450 operations. Firestore rejects a batch above 500, and the
master-password sweep used to dirty every row at once, so any vault over that size failed
to sync outright (#21).

### Tombstones

Soft-deleted rows that have been pushed and are older than 30 days are removed from both
sides. They previously accumulated for ever (#24).

### Vault config

`vaults/{uid}` carries the salt, the verification blob, **and the wrapped vault key**. The
last of these is what lets a second device reach the key the rows are encrypted under;
without it, adopting a remote config left a vault whose master password verified and whose
contents were unreachable (#4).

If a sync finds a remote config whose salt differs from the local one, sync switches itself
off and says so. Uploading local rows into a vault keyed differently would fill it with
blobs nothing could ever read.

The config is published when the cloud holds none, **and** when the cloud holds one that
matches but carries no wrapped vault key. That second case is not hypothetical: a config
published before the vault was converted to the vault-key layout has a salt and a
verification blob and nothing else, and conversion leaves the salt alone, so the older
one-branch logic saw a matching config and republished nothing for ever (#64). The write
merges, so republishing adds the missing key rather than rewriting what is there.

The three outcomes live in `SyncMerge.configAction`, away from Firestore, for the same
reason the row rules do.

**Not yet built:** joining an account that already holds a different vault. It is refused
with instructions rather than offering to merge or replace. That flow needs a second device
to design against.

### The desktop reader

`:desktop --cloud` is a second client against the same documents, and it reads only. It
authenticates as an ordinary user — loopback OAuth, then Identity Toolkit — so the security
rules below apply to it unchanged. Nothing about push, cursors or conflicts is involved,
because it never writes.

It is what makes the wrapped vault key visibly load-bearing. Without `vaultKeyCiphertext`
in `vaults/{uid}`, the desktop can verify the master password and still not reach a single
credential; it refuses that case rather than showing an empty vault.

The desktop can now also **create** an entry, and only create. A fresh UUID cannot collide
with an existing row, so no merge decision is ever required; the write carries a
create-only precondition the server enforces, and sets `serverUpdatedAt` from the server
clock in the same operation so the phone's ordered pull can see it.

The unbuilt flow above stays unbuilt, and editing and deleting from the desktop stay
unbuilt with it. Those are the operations that would put two real writers against one row
for the first time.

Confirmed against the owner's live vault: sign-in, config fetch, unlock and decryption of
every row. It required the #64 fix first — before that the cloud config carried no wrapped
vault key and the reader correctly refused to pretend the vault was empty.

## Security note

The Firestore vault document stores `salt`, `verificationCiphertext`, and
`verificationIv`. Anyone with read access to the account — including a Firebase project
administrator — can mount an **offline brute-force attack on the master password** against
the verification blob, at Argon2id cost per guess.

This is inherent to storing the config remotely for cross-device unlock, and it is the
reason the Argon2 parameters in [SECURITY.md](SECURITY.md) must not be weakened.

The rules that restrict `vaults/{uid}` to `request.auth.uid == uid` are load-bearing, and
they now live in [`firestore.rules`](../firestore.rules) at the repository root rather than
only in the console. Two things about them are worth knowing:

- **Rules do not cascade into subcollections.** `vaults/{uid}` and
  `vaults/{uid}/credentials/{id}` need separate blocks. A rule covering only the parent
  denies every credential read, and that presents as a vault that opens and holds nothing.
- **A project left in test mode allows unauthenticated access** until its expiry date, and
  denies everything after it. Either state is wrong here: the first exposes every vault in
  the project, the second breaks sync on a date nobody remembers.

Deploy with `firebase deploy --only firestore:rules`, or paste the file into the console.
