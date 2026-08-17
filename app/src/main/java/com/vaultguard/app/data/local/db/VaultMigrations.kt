package com.vaultguard.app.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for `vault.db`.
 *
 * Destructive fallback is deliberately **not** enabled on the database builder: a
 * migration that fails must crash loudly rather than quietly recreate an empty vault.
 */
object VaultMigrations {

    /**
     * Adds `passwordChangedAt`, separating "when the password changed" from "when the row
     * was written" (finding #29).
     *
     * Existing rows are backfilled from `updatedAt`. That is a best guess rather than the
     * truth — the real date was never recorded, and `updatedAt` is an upper bound on it.
     * It errs toward reporting passwords as *newer* than they are, which is the safer
     * direction for a backfill: it under-warns rather than crying wolf on every entry.
     *
     * The `DEFAULT 0` must match the `@ColumnInfo(defaultValue = "0")` on the entity, or
     * Room's schema validation rejects the migrated table at open time.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE credentials ADD COLUMN passwordChangedAt INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("UPDATE credentials SET passwordChangedAt = updatedAt")
        }
    }

    /**
     * Re-backfills `passwordChangedAt` from `createdAt`, correcting MIGRATION_1_2.
     *
     * That migration copied `updatedAt`, on the reasoning that it was an upper bound on
     * when the password changed. It was — until the master-password re-encryption sweep
     * rewrote `updatedAt` on every row. Any vault whose password had been changed since
     * the entries were created therefore migrated with every entry reporting an age of
     * "since the last password change", which for this install was a few days.
     *
     * `createdAt` is untouched by the sweep and is a *lower* bound: correct for any entry
     * whose password was never rotated, and too old for the rest. That is the better
     * direction to be wrong in for a feature whose whole purpose is to prompt rotation —
     * over-warning nags, under-warning silently hides stale passwords.
     *
     * Safe to apply unconditionally: genuine per-entry rotation timestamps only start
     * being recorded from v2 onwards, and none can predate this migration.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE credentials SET passwordChangedAt = createdAt")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
