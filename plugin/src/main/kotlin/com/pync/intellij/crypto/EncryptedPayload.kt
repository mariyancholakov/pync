package com.pync.intellij.crypto

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val authTag: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPayload) return false
        return ciphertext.contentEquals(other.ciphertext) &&
            nonce.contentEquals(other.nonce) &&
            authTag.contentEquals(other.authTag)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}
