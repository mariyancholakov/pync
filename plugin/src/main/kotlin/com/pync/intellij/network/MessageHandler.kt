package com.pync.intellij.network

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Handles the length-prefixed JSON wire protocol for a single TCP connection.
 *
 * Wire format per message:
 *   [4 bytes big-endian int32: payload length][N bytes: UTF-8 JSON PyncMessage]
 *
 * Instances are shared across connections; all mutable state is per-call.
 */
class MessageHandler(
    private val registry: PeerRegistry,
    private val ownPublicKeyB64: String,
) {
    private val log = thisLogger()
    private val json = Json { ignoreUnknownKeys = true }

    /** Called by the application layer to handle incoming SECRET_SHARE frames. */
    var onSecretShare: ((PyncMessage) -> Unit)? = null

    companion object {
        private const val MAX_MESSAGE_BYTES = 1 * 1024 * 1024  // 1 MB sanity cap
    }

    /**
     * Reads and dispatches messages from [channel] until the peer closes the connection.
     * Intended to run on [kotlinx.coroutines.Dispatchers.IO]; safe to call from multiple
     * coroutines simultaneously because each call owns its own channel.
     */
    fun handle(channel: SocketChannel) {
        channel.use {
            try {
                val input = DataInputStream(it.socket().inputStream)
                while (true) {
                    val length = try {
                        input.readInt()
                    } catch (_: EOFException) {
                        break  // clean disconnect
                    }
                    if (length <= 0 || length > MAX_MESSAGE_BYTES) {
                        log.warn("Rejecting message with invalid length $length from ${it.remoteAddress}")
                        break
                    }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    val msg = try {
                        json.decodeFromString<PyncMessage>(bytes.decodeToString())
                    } catch (e: SerializationException) {
                        log.warn("Malformed message from ${it.remoteAddress}: ${e.message}")
                        break
                    }
                    dispatch(msg, channel)
                }
            } catch (_: IOException) {
                // peer reset or channel closed during graceful shutdown — expected
            }
        }
    }

    /**
     * Serializes [msg] and writes it as a length-prefixed frame to [channel].
     * Loops until all bytes are written (NIO write may be partial).
     */
    fun writeMessage(channel: SocketChannel, msg: PyncMessage) {
        val payload = json.encodeToString(PyncMessage.serializer(), msg).encodeToByteArray()
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size)
        buffer.putInt(payload.size)
        buffer.put(payload)
        buffer.flip()
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun dispatch(msg: PyncMessage, channel: SocketChannel) {
        when (msg.type) {
            MessageType.HELLO -> {
                // If we already know this peer (via mDNS) mark them online; the mDNS path
                // handles first-time registration.
                registry.getPeer(msg.senderId)?.let { peer ->
                    if (!peer.isOnline) registry.onPeerDiscovered(peer.copy(isOnline = true))
                }
                writeMessage(channel, makeAck())
            }
            MessageType.PING -> writeMessage(channel, makeAck())
            MessageType.SECRET_SHARE -> onSecretShare?.invoke(msg)
            MessageType.ACK -> { /* terminal response, nothing to do */ }
            else -> log.warn("Unknown message type '${msg.type}' from ${msg.senderId.take(8)}")
        }
    }

    private fun makeAck() = PyncMessage(
        type = MessageType.ACK,
        senderId = ownPublicKeyB64,
        payload = "",
        timestamp = System.currentTimeMillis(),
    )
}
