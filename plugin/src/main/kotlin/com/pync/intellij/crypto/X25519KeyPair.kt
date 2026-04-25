package com.pync.intellij.crypto

import java.security.PrivateKey
import java.security.PublicKey

data class X25519KeyPair(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
)
