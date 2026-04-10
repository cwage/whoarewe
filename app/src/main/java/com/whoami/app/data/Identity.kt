package com.whoami.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
data class Identity(
    @PrimaryKey val id: Long = 1,
    val displayName: String,
    val publicKey: String,
    val createdAt: Long = System.currentTimeMillis()
)
