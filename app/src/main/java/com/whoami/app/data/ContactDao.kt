package com.whoami.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
