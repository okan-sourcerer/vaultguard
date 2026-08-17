package com.vaultguard.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MIGRATION_1_2 against the real committed v1 schema.
 *
 * Instrumented because Room's `MigrationTestHelper` needs a device, and because the
 * database is opened through SQLCipher — the migration has to work against an encrypted
 * file, not a plain one.
 *
 * Uses its own database name, so it never touches the real `vault.db`.
 */
@RunWith(AndroidJUnit4::class)
class VaultMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
        val PASSPHRASE = "migration-test-passphrase".toByteArray()
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(PASSPHRASE)
    )

    @Before
    fun clean() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB)
    }

    private fun insertV1Row(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        createdAt: Long,
        updatedAt: Long,
        isDeleted: Int = 0
    ) {
        db.execSQL(
            "INSERT INTO credentials " +
                "(id, encryptedPayload, iv, createdAt, updatedAt, syncedAt, isDeleted) " +
                "VALUES (?, ?, ?, ?, ?, NULL, ?)",
            arrayOf(id, byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), createdAt, updatedAt, isDeleted)
        )
    }

    @Test
    fun migrate1To2_addsColumnAndKeepsEveryRow() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Row(db, "a", createdAt = 1_000, updatedAt = 5_000)
            insertV1Row(db, "b", createdAt = 2_000, updatedAt = 6_000)
            insertV1Row(db, "c", createdAt = 3_000, updatedAt = 7_000, isDeleted = 1)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, VaultMigrations.MIGRATION_1_2)

        db.query("SELECT COUNT(*) FROM credentials").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("no row may be lost by the migration", 3, cursor.getInt(0))
        }
    }

    @Test
    fun migrate1To2_backfillsPasswordChangedAtFromUpdatedAt() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Row(db, "a", createdAt = 1_000, updatedAt = 5_000)
            insertV1Row(db, "b", createdAt = 2_000, updatedAt = 6_000)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, VaultMigrations.MIGRATION_1_2)

        db.query("SELECT id, updatedAt, passwordChangedAt FROM credentials ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("a", cursor.getString(0))
            assertEquals(5_000L, cursor.getLong(1))
            assertEquals(5_000L, cursor.getLong(2))

            assertTrue(cursor.moveToNext())
            assertEquals("b", cursor.getString(0))
            assertEquals(6_000L, cursor.getLong(2))
        }
    }

    @Test
    fun migrate1To2_preservesEncryptedPayloadBytes() {
        // The whole point of the vault. A migration that silently altered ciphertext would
        // be indistinguishable from corruption.
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val iv = byteArrayOf(1, 1, 2, 3, 5, 8)

        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO credentials " +
                    "(id, encryptedPayload, iv, createdAt, updatedAt, syncedAt, isDeleted) " +
                    "VALUES ('keep', ?, ?, 1, 2, NULL, 0)",
                arrayOf(payload, iv)
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, VaultMigrations.MIGRATION_1_2)

        db.query("SELECT encryptedPayload, iv FROM credentials WHERE id = 'keep'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(payload.contentEquals(cursor.getBlob(0)))
            assertTrue(iv.contentEquals(cursor.getBlob(1)))
        }
    }

    @Test
    fun migrate1To2_onEmptyDatabase() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, VaultMigrations.MIGRATION_1_2)

        db.query("SELECT COUNT(*) FROM credentials").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate2To3_rebackfillsPasswordAgeFromCreatedAt() {
        // MIGRATION_1_2 sourced passwordChangedAt from updatedAt, which the master-password
        // re-encryption sweep had already rewritten on every row. createdAt survives that
        // sweep, so it is the better estimate.
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Row(db, "a", createdAt = 1_000, updatedAt = 9_999)
            insertV1Row(db, "b", createdAt = 2_000, updatedAt = 9_999)
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, VaultMigrations.MIGRATION_1_2).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, VaultMigrations.MIGRATION_2_3)

        db.query("SELECT id, createdAt, passwordChangedAt FROM credentials ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_000L, cursor.getLong(2))
            assertTrue(cursor.moveToNext())
            assertEquals(2_000L, cursor.getLong(2))
        }
    }

    @Test
    fun migrateAll_fromV1_landsOnCreatedAt() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Row(db, "a", createdAt = 1_000, updatedAt = 9_999)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *VaultMigrations.ALL)

        db.query("SELECT passwordChangedAt FROM credentials").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_000L, cursor.getLong(0))
        }
    }
}
