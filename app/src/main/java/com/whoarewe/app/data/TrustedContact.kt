package com.whoarewe.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trusted_contacts")
data class TrustedContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val publicKey: String,
    val totpSecret: String,
    val verifiedAt: Long = System.currentTimeMillis(),
    val notes: String? = null
)
