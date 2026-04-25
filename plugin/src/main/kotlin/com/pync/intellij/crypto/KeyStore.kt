package com.pync.intellij.crypto

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Attribute
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Manages the user's long-term X25519 identity key pair.
 *
 * The key ID (a UUID) is persisted in IntelliJ's settings XML so we can locate
 * the entry in PasswordSafe across restarts. Actual key material never touches
 * disk unencrypted — it lives exclusively in PasswordSafe.
 */
@Service(Service.Level.APP)
@State(
    name = "PyncKeyStore",
    storages = [Storage("pync-keystore.xml")],
)
class KeyStore : PersistentStateComponent<KeyStore.State> {

    class State {
        @Attribute
        var keyId: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(): KeyStore =
            ApplicationManager.getApplication().getService(KeyStore::class.java)
    }

    /**
     * Returns the stored X25519 private key, generating and persisting one on first call.
     * Must not be called on the EDT — PasswordSafe may block.
     */
    fun getOrCreateIdentityKey(): PrivateKey = loadPrivateKey() ?: generateAndStore()

    /**
     * Returns the X.509 SubjectPublicKeyInfo bytes of the identity public key,
     * suitable for sending to peers. Calls [getOrCreateIdentityKey] if needed.
     */
    fun getPublicKey(): ByteArray {
        return loadPublicKeyBytes() ?: run {
            getOrCreateIdentityKey()
            checkNotNull(loadPublicKeyBytes()) { "Key was just generated but public key is missing" }
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun generateAndStore(): PrivateKey {
        val keyPair = CryptoManager.generateIdentityKeyPair()
        val keyId = UUID.randomUUID().toString()
        val b64 = Base64.getUrlEncoder().withoutPadding()

        PasswordSafe.instance.set(
            credAttrs(keyId, "priv"),
            Credentials("pync", b64.encodeToString(keyPair.privateKey.encoded)),
        )
        PasswordSafe.instance.set(
            credAttrs(keyId, "pub"),
            Credentials("pync", b64.encodeToString(keyPair.publicKey.encoded)),
        )

        myState.keyId = keyId
        return keyPair.privateKey
    }

    private fun loadPrivateKey(): PrivateKey? {
        val keyId = myState.keyId.takeIf { it.isNotEmpty() } ?: return null
        val encoded = PasswordSafe.instance.get(credAttrs(keyId, "priv"))
            ?.getPasswordAsString()
            ?: return null
        val bytes = Base64.getUrlDecoder().decode(encoded)
        val kf = KeyFactory.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME)
        return kf.generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun loadPublicKeyBytes(): ByteArray? {
        val keyId = myState.keyId.takeIf { it.isNotEmpty() } ?: return null
        val encoded = PasswordSafe.instance.get(credAttrs(keyId, "pub"))
            ?.getPasswordAsString()
            ?: return null
        return Base64.getUrlDecoder().decode(encoded)
    }

    private fun credAttrs(keyId: String, suffix: String) =
        CredentialAttributes(generateServiceName("pync-identity", "$keyId-$suffix"))
}
