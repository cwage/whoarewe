package com.whoarewe.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM identity WHERE id = 1")
    fun getIdentity(): Flow<Identity?>

    @Query("SELECT * FROM identity WHERE id = 1")
    suspend fun getIdentityOnce(): Identity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveIdentity(identity: Identity)

    @Query("SELECT * FROM trusted_contacts ORDER BY displayName ASC")
    fun getAllContacts(): Flow<List<TrustedContact>>

    @Query("SELECT * FROM trusted_contacts WHERE id = :id")
    suspend fun getContactById(id: Long): TrustedContact?

    @Query("SELECT * FROM trusted_contacts WHERE publicKey = :publicKey")
    suspend fun getContactByPublicKey(publicKey: String): TrustedContact?

    @Insert
    suspend fun insertContact(contact: TrustedContact): Long

    @Update
    suspend fun updateContact(contact: TrustedContact)

    @Delete
    suspend fun deleteContact(contact: TrustedContact)

    @Query("DELETE FROM trusted_contacts WHERE id = :id")
    suspend fun deleteContactById(id: Long)

    /**
     * Atomically replace an existing contact with a new one. The old row's
     * `id` is the handle — the new row is inserted with a fresh auto-generated
     * `id` because the shared secret is different and anything holding the
     * old `id` is stale by construction.
     *
     * Runs in a single Room transaction so the DB never sits in an
     * intermediate "zero contacts named Alice" state — observers of
     * [getAllContacts] will see either the old row or the new row, never
     * neither. This is the "Replace existing contact" branch of the key-
     * change warning dialog described in cwage/whoarewe#33.
     */
    @Transaction
    suspend fun replaceContact(oldId: Long, replacement: TrustedContact) {
        deleteContactById(oldId)
        insertContact(replacement)
    }
}
