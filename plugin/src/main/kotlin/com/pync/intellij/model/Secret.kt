package com.pync.intellij.model

import kotlinx.serialization.Serializable

@Serializable
data class Secret(
    val id: String,
    val key: String,
    val encryptedValue: String,
    val iv: String,
    val senderPeerId: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SecretEnvelope(
    val secret: Secret,
    val recipientPeerId: String,
    val encryptedSymmetricKey: String,
)
