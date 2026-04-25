const sodium = require('sodium-native')
const b4a = require('b4a')

function deriveKey (passphrase, workspaceName) {
  const salt = b4a.alloc(32)
  sodium.crypto_generichash(salt, b4a.from(workspaceName))

  const prk = b4a.alloc(32)
  sodium.crypto_generichash(prk, b4a.from(passphrase), salt)

  const info = b4a.concat([b4a.from('pync-encryption-key'), b4a.from([1])])
  const key = b4a.alloc(32)
  sodium.crypto_generichash(key, info, prk)

  return key
}

function encrypt (value, key) {
  const nonce = b4a.alloc(sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  sodium.randombytes_buf(nonce)

  const plaintext = b4a.from(value)
  const ciphertext = b4a.alloc(plaintext.length + sodium.crypto_aead_xchacha20poly1305_ietf_ABYTES)

  sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
    ciphertext, plaintext, null, null, nonce, key
  )

  return JSON.stringify({
    nonce: b4a.toString(nonce, 'hex'),
    ciphertext: b4a.toString(ciphertext, 'hex')
  })
}

function decrypt (encryptedJson, key) {
  const { nonce, ciphertext } = JSON.parse(encryptedJson)
  const nonceBuf = b4a.from(nonce, 'hex')
  const ciphertextBuf = b4a.from(ciphertext, 'hex')
  const plaintext = b4a.alloc(ciphertextBuf.length - sodium.crypto_aead_xchacha20poly1305_ietf_ABYTES)

  sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
    plaintext, null, ciphertextBuf, null, nonceBuf, key
  )

  return b4a.toString(plaintext)
}

module.exports = { deriveKey, encrypt, decrypt }
