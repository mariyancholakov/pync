package com.pync.intellij.network

/**
 * A discovered pync peer.
 *
 * [id] is the peer's base64url-encoded X25519 public key and serves as the
 * stable identity across reconnects — equality and hash use it exclusively.
 */
data class Peer(
    val id: String,
    val displayName: String,
    val publicKeyBytes: ByteArray,
    val ip: String,
    val port: Int,
    val isOnline: Boolean = true,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Peer && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Peer(displayName=$displayName, ip=$ip:$port, online=$isOnline, id=${id.take(8)}...)"
}
