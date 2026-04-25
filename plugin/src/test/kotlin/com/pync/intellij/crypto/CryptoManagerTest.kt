package com.pync.intellij.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.Security
import javax.crypto.AEADBadTagException

class CryptoManagerTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerProvider() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    // ── key generation ───────────────────────────────────────────────────────

    @Test
    fun `key generation produces distinct key pairs`() {
        val kp1 = CryptoManager.generateIdentityKeyPair()
        val kp2 = CryptoManager.generateIdentityKeyPair()
        assertFalse(kp1.publicKey.encoded.contentEquals(kp2.publicKey.encoded))
        assertFalse(kp1.privateKey.encoded.contentEquals(kp2.privateKey.encoded))
    }

    @Test
    fun `generated public key round-trips through serialization`() {
        val kp = CryptoManager.generateIdentityKeyPair()
        val bytes = CryptoManager.publicKeyToBytes(kp.publicKey)
        val reconstructed = CryptoManager.publicKeyFromBytes(bytes)
        assertArrayEquals(kp.publicKey.encoded, reconstructed.encoded)
    }

    // ── ECDH symmetry ────────────────────────────────────────────────────────

    @Test
    fun `ECDH shared secrets match in both directions`() {
        val alice = CryptoManager.generateIdentityKeyPair()
        val bob = CryptoManager.generateIdentityKeyPair()

        val secretAlice = CryptoManager.deriveSharedSecret(alice.privateKey, bob.publicKey)
        val secretBob = CryptoManager.deriveSharedSecret(bob.privateKey, alice.publicKey)

        assertArrayEquals(secretAlice, secretBob)
        assertEquals(32, secretAlice.size)
    }

    @Test
    fun `Alice can decrypt what Bob encrypted with the shared secret`() {
        val alice = CryptoManager.generateIdentityKeyPair()
        val bob = CryptoManager.generateIdentityKeyPair()
        val secretAlice = CryptoManager.deriveSharedSecret(alice.privateKey, bob.publicKey)
        val secretBob = CryptoManager.deriveSharedSecret(bob.privateKey, alice.publicKey)

        val payload = CryptoManager.encryptSecret("DATABASE_URL=postgres://prod:5432/app", secretBob)
        val plaintext = CryptoManager.decryptSecret(payload, secretAlice)

        assertEquals("DATABASE_URL=postgres://prod:5432/app", plaintext)
    }

    @Test
    fun `Bob can decrypt what Alice encrypted with the shared secret`() {
        val alice = CryptoManager.generateIdentityKeyPair()
        val bob = CryptoManager.generateIdentityKeyPair()
        val secretAlice = CryptoManager.deriveSharedSecret(alice.privateKey, bob.publicKey)
        val secretBob = CryptoManager.deriveSharedSecret(bob.privateKey, alice.publicKey)

        val payload = CryptoManager.encryptSecret("STRIPE_SECRET_KEY=sk_live_abc123", secretAlice)
        val plaintext = CryptoManager.decryptSecret(payload, secretBob)

        assertEquals("STRIPE_SECRET_KEY=sk_live_abc123", plaintext)
    }

    // ── GCM authentication ───────────────────────────────────────────────────

    @Test
    fun `tampering with ciphertext byte throws AEADBadTagException`() {
        val sharedKey = sharedKeyForSelf()
        val payload = CryptoManager.encryptSecret("secret-value", sharedKey)

        val tampered = EncryptedPayload(
            ciphertext = payload.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() },
            nonce = payload.nonce,
            authTag = payload.authTag,
        )

        assertThrows<AEADBadTagException> { CryptoManager.decryptSecret(tampered, sharedKey) }
    }

    @Test
    fun `tampering with auth tag throws AEADBadTagException`() {
        val sharedKey = sharedKeyForSelf()
        val payload = CryptoManager.encryptSecret("secret-value", sharedKey)

        val tampered = EncryptedPayload(
            ciphertext = payload.ciphertext,
            nonce = payload.nonce,
            authTag = payload.authTag.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() },
        )

        assertThrows<AEADBadTagException> { CryptoManager.decryptSecret(tampered, sharedKey) }
    }

    @Test
    fun `tampering with nonce throws AEADBadTagException`() {
        val sharedKey = sharedKeyForSelf()
        val payload = CryptoManager.encryptSecret("secret-value", sharedKey)

        val tampered = EncryptedPayload(
            ciphertext = payload.ciphertext,
            nonce = payload.nonce.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() },
            authTag = payload.authTag,
        )

        assertThrows<AEADBadTagException> { CryptoManager.decryptSecret(tampered, sharedKey) }
    }

    // ── IND-CPA ──────────────────────────────────────────────────────────────

    @Test
    fun `encrypting the same plaintext twice produces different nonces`() {
        val sharedKey = sharedKeyForSelf()
        val p1 = CryptoManager.encryptSecret("same-plaintext", sharedKey)
        val p2 = CryptoManager.encryptSecret("same-plaintext", sharedKey)
        assertFalse(p1.nonce.contentEquals(p2.nonce))
    }

    // ── edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `roundtrip preserves complex plaintext including special characters`() {
        val sharedKey = sharedKeyForSelf()
        val secret = "postgres://user:p@\$\$w0rd!@db.internal:5432/prod?sslmode=require&schema=public"

        val payload = CryptoManager.encryptSecret(secret, sharedKey)
        assertEquals(secret, CryptoManager.decryptSecret(payload, sharedKey))
    }

    @Test
    fun `roundtrip preserves empty string`() {
        val sharedKey = sharedKeyForSelf()
        val payload = CryptoManager.encryptSecret("", sharedKey)
        assertEquals("", CryptoManager.decryptSecret(payload, sharedKey))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Produces a valid 32-byte shared secret using a single key pair (convenient for unit tests). */
    private fun sharedKeyForSelf(): ByteArray {
        val kp = CryptoManager.generateIdentityKeyPair()
        return CryptoManager.deriveSharedSecret(kp.privateKey, kp.publicKey)
    }
}
