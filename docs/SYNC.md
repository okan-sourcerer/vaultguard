# Cloud sync

Optional replication of encrypted credential blobs to Firestore, keyed to a Firebase Auth
user. Describes what the code does now, why it loses data, and the target design.

**Operating principle going forward: the device is the source of truth; the cloud is a
rebuildable replica.** The vault can always be reconstructed from the device, so no sync
fix needs a remote data migration — wiping and re-pushing is an acceptable recovery.
This assumption is what keeps the sync rework tractable.

## Current design

### Authentication

`FirebaseSyncService.ensureAuthenticated()` signs in **anonymously** if there is no
current user. Every sync entry point calls it. There is no consent gate — pull-to-refresh
on the vault list is enough to create a cloud vault and upload every credential (#15),
while Settings still displays "Local only".

Signing in with Google tries `linkWithCredential` first, which preserves the anonymous
UID and therefore the existing vault path. If linking fails — typically because that
Google account already exists in the project — it falls back to `signInWithCredential`
and hands the old UID to `migrateFromAnonymousUser` to copy documents across.

Sign-out calls `signOutAndGoAnonymous()`, which immediately signs back in anonymously.
The UI says "Cloud sync disabled"; the next sync re-uploads the whole vault under a fresh
anonymous UID (#16). There is no way to delete cloud data from inside the app.

### The sync cycle

```
fullSync()
  ├─ ensureAuthenticated()
  ├─ pullVaultConfig()  → if absent and setup is complete, pushVaultConfig()
  ├─ lastSync = prefs["last_sync_time_{uid}"]        (0 if never)
  ├─ pushChanges(lastSync)
  │     modified = SELECT * FROM credentials WHERE updatedAt > lastSync
  │     one Firestore batch, set() per row
  │     then mark each row syncedAt = now
  ├─ pullChanges(lastSync)
  │     query credentials WHERE updatedAt > lastSync
  │     for each remote row, upsert if:
  │        local is absent, OR
  │        remote.updatedAt > local.updatedAt, OR
  │        (equal timestamps AND local.syncedAt != null)
  └─ prefs["last_sync_time_{uid}"] = now
```

Conflict resolution is last-write-wins on a client wall clock, with an arbitrary tiebreak.

## Why it loses data

Four defects, all rooted in using device wall-clock time as a sync cursor.

### 1. The write window (#19)

The cursor is stamped *after* the network round-trip, but the query ran before it:

```
t0  read lastSync
t1  SELECT … WHERE updatedAt > t0     ← snapshot taken here
t2  user saves a credential           ← updatedAt = t2, not in the snapshot
t3  write lastSync = t3
```

The row written at `t2` has `updatedAt < t3`, so the next cycle's `WHERE updatedAt > t3`
skips it. It is never pushed. Not delayed — **lost permanently**, until something else
happens to touch that row.

### 2. Clock skew across devices (#20)

`updatedAt` is written by whichever device made the change, but compared against the
*reading* device's cursor. A phone running ten minutes behind writes rows that a
faster device's `whereGreaterThan(updatedAt, lastSync)` never returns. The change is
invisible to the other device forever.

There is no clock-skew tolerance, no server timestamp, and no vector clock.

### 3. Batch size (#21)

`pushChanges` puts every modified row into a single `WriteBatch`. Firestore caps a batch
at 500 operations. Normally fine — but changing the master password rewrites *every* row
with a new `updatedAt`, so the next sync tries to push the entire vault at once. Any vault
over 500 entries fails to sync immediately after a password change, which is exactly when
sync matters most.

`migrateFromAnonymousUser` has the same unbounded batch.

### 4. Silent conflict loss (#23)

`remote.updatedAt == local.updatedAt && local.syncedAt != null` decides ties. Two devices
editing the same credential means one edit is discarded with no record and no user-visible
signal.

## Other broken paths

**Adopting a remote vault (#4).** On Google sign-in, if a remote config exists with a
different salt, the app adopts the remote salt and verification blob, then calls
`fullSync()` *before* locking. Two things go wrong:

- The sync pushes local rows still encrypted under the **old local key** into the shared
  remote vault, contaminating it with blobs that no device can decrypt.
- After re-unlocking with the adopted salt, every pre-existing local credential is
  undecryptable and vanishes from the list.

**Config migration skipped for empty vaults (#22).** `migrateFromAnonymousUser` returns
early when the anonymous vault has no credential documents — before the block that copies
the salt and verification blob. A user who sets a master password and signs in before
adding any entry never gets their config migrated.

**Tombstones accumulate forever (#24).** Soft-deleted rows sync as `isDeleted: true` and
are never purged from either side.

## Target design

Ordered by dependency. Corresponds to chunk 9 of [REMEDIATION-PLAN.md](REMEDIATION-PLAN.md).

### Replace the wall-clock cursor with a monotonic local revision

Add a per-row `revision` (monotonically increasing per device) and a `lastPushedRevision`
cursor. Pushing becomes "everything above my cursor", which is immune to clock skew and
to the write window because the cursor advances to the **highest revision actually
pushed**, not to "now".

For the pull direction, use a Firestore **server timestamp** (`FieldValue.serverTimestamp()`)
as the ordering key and store the last-seen server timestamp as the pull cursor. Server
timestamps are assigned by Firestore, so all devices agree on the ordering regardless of
their local clocks.

This means a schema change (new column) and a Firestore field addition. Because the cloud
is disposable, the migration is: add the column, reset both cursors to zero, wipe the
remote collection, re-push everything once.

### Chunk the batches

Split into 450-operation batches with a safety margin, committed sequentially. Applies to
`pushChanges` and `migrateFromAnonymousUser`.

### Make sync opt-in and reversible

- No implicit `signInAnonymously()`. Sync operations against an unauthenticated user
  should fail loudly rather than silently provisioning a cloud vault.
- Pull-to-refresh must be a no-op when sync is not enabled.
- "Local only" in Settings must be true when it is displayed.
- Add a real **"Disable sync and delete cloud data"** action that removes
  `vaults/{uid}` and its subcollection, then clears the local cursors.

### Fix the adopt-remote-vault flow

Adopting a remote salt means the local vault is encrypted under the wrong key. The correct
sequence is:

1. Detect the salt mismatch **before** any sync.
2. Re-encrypt local credentials from the old key to the new one — which requires the user
   to enter the master password for the remote vault, and requires the old session key to
   still be live. If the passwords differ, the local rows cannot be converted and the user
   must choose explicitly: keep local (and abandon the remote vault) or adopt remote (and
   export local first).
3. Only sync after the local vault is consistent with the adopted config.

Never push rows encrypted under a key the destination vault does not use.

### Purge tombstones

Delete rows soft-deleted more than 30 days ago on both sides, once both have observed the
tombstone (`syncedAt != null`).

### Surface conflicts

At minimum, log and count them; ideally keep the loser as a duplicate entry rather than
discarding it silently.

## Security note

The Firestore vault document stores `salt`, `verificationCiphertext`, and
`verificationIv`. Anyone with read access to the account — including a Firebase project
administrator — can mount an **offline brute-force attack on the master password** against
the verification blob, at Argon2id cost per guess.

This is inherent to storing the config remotely for cross-device unlock, and it is the
reason the Argon2 parameters in [SECURITY.md](SECURITY.md) must not be weakened. Firestore
security rules restricting `vaults/{uid}` to `request.auth.uid == uid` are load-bearing
and should be verified in the console — they are not currently checked into this repo.
