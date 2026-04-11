package com.whoarewe.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Trusted contact row. The shared TOTP secret for each contact is stored
 * encrypted at rest (cwage/whoarewe#32): [encryptedTotpSecret] holds the
 * AES-256-GCM ciphertext and [totpSecretIv] the 12-byte IV produced at
 * pair time by [com.whoarewe.app.crypto.TotpSecretCodec.encrypt], under
 * the per-identity DEK held inside the biometric-wrapped identity blob.
 *
 * The plaintext of the TOTP secret never touches disk — pair time writes
 * only the ciphertext, and every subsequent tick reads from an in-memory
 * cache (`WhoAreWeViewModel.totpSecretCache`) populated once at the
 * post-biometric unlock event.
 *
 * Kotlin's generated `equals` and `hashCode` use reference equality for
 * `ByteArray` fields, so both are overridden to content-based comparison.
 * Room itself uses the class as a data holder rather than a `Set` key,
 * but tests and the collision glue compare rows for equality, so getting
 * this right is worth the boilerplate.
 */
@Entity(tableName = "trusted_contacts")
data class TrustedContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val publicKey: String,
    val encryptedTotpSecret: ByteArray,
    val totpSecretIv: ByteArray,
    val verifiedAt: Long = System.currentTimeMillis(),
    val notes: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedContact) return false
        return id == other.id &&
            displayName == other.displayName &&
            publicKey == other.publicKey &&
            encryptedTotpSecret.contentEquals(other.encryptedTotpSecret) &&
            totpSecretIv.contentEquals(other.totpSecretIv) &&
            verifiedAt == other.verifiedAt &&
            notes == other.notes
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + encryptedTotpSecret.contentHashCode()
        result = 31 * result + totpSecretIv.contentHashCode()
        result = 31 * result + verifiedAt.hashCode()
        result = 31 * result + (notes?.hashCode() ?: 0)
        return result
    }
}
