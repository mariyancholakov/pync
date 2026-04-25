package com.pync.intellij.network

import kotlinx.serialization.Serializable

@Serializable
data class PyncMessage(
    val type: String,
    val senderId: String,
    val payload: String,
    val timestamp: Long,
)

object MessageType {
    const val HELLO = "HELLO"
    const val SECRET_SHARE = "SECRET_SHARE"
    const val ACK = "ACK"
    const val PING = "PING"
}
