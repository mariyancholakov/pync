package com.pync.intellij.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.XDHParameterSpec
import java.security.KeyAgreement
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val BC = BouncyCastleProvider.PROVIDER_NAME
    private const val ALGORITHM = "X25519"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val NONCE_BYTES = 12

    val b64Encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    val b64Decoder: Base64.Decoder = Base64.getUrlDecoder()

    init {
        if (Security.getProvider(BC) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generateIdentityKeyPair(): X25519KeyPair {
        val kpg = KeyPairGenerator.getInstance(ALGORITHM, BC)
        kpg.initialize(XDHParameterSpec(XDHParameterSpec.X25519), SecureRandom())
        val jceKP = kpg.generateKeyPair()
        return X25519KeyPair(jceKP.private, jceKP.public)
    }

    fun deriveSharedSecret(myPrivateKey: PrivateKey, theirPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance(ALGORITHM, BC)
        ka.init(myPrivateKey)
        ka.doPhase(theirPublicKey, true)
        return ka.generateSecret()
    }

    fun encryptSecret(plaintext: String, sharedKey: ByteArray): EncryptedPayload {
        require(sharedKey.size == 32) { "sharedKey must be 32 bytes for AES-256" }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(sharedKey, "AES")
        val cipher = Cipher.getInstance(AES_GCM, BC)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        // BC GCM output: ciphertext || 16-byte tag
        val gcmOutput = cipher.doFinal(plaintext.encodeToByteArray())
        val ciphertext = gcmOutput.copyOfRange(0, gcmOutput.size - GCM_TAG_BYTES)
        val authTag = gcmOutput.copyOfRange(gcmOutput.size - GCM_TAG_BYTES, gcmOutput.size)
        return EncryptedPayload(ciphertext, nonce, authTag)
    }

    fun decryptSecret(payload: EncryptedPayload, sharedKey: ByteArray): String {
        require(sharedKey.size == 32) { "sharedKey must be 32 bytes for AES-256" }
        val secretKey = SecretKeySpec(sharedKey, "AES")
        val cipher = Cipher.getInstance(AES_GCM, BC)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, payload.nonce))
        // Reassemble ciphertext || tag as expected by BC's GCM decrypt
        return cipher.doFinal(payload.ciphertext + payload.authTag).decodeToString()
    }

    /** Encodes a PublicKey to bytes suitable for transmission to a peer. */
    fun publicKeyToBytes(publicKey: PublicKey): ByteArray = publicKey.encoded

    /** Reconstructs a PublicKey from bytes produced by [publicKeyToBytes]. */
    fun publicKeyFromBytes(bytes: ByteArray): PublicKey {
        val kf = KeyFactory.getInstance(ALGORITHM, BC)
        return kf.generatePublic(X509EncodedKeySpec(bytes))
    }
}
