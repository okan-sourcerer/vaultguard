package com.vaultguard.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    val encryptedPayload: ByteArray,
    val iv: ByteArray,
    val createdAt: Long,
    /** Any write to this row: an edit, a pin toggle, or a re-encryption sweep. */
    val updatedAt: Long,
    /**
     * When the *password* last changed, as distinct from when the row was last written.
     *
     * [updatedAt] was doing both jobs, so pinning an entry or editing its notes reset the
     * displayed password age to "Today" and cleared it from the stale-password count
     * (finding #29). A master-password change touched every row and reset all of them.
     *
     * `defaultValue` is declared so the schema Room expects matches what MIGRATION_1_2
     * actually creates; without it Room's validation rejects the migrated table.
     */
    @ColumnInfo(defaultValue = "0")
    val passwordChangedAt: Long = 0L,
    val syncedAt: Long? = null,
    val isDeleted: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
