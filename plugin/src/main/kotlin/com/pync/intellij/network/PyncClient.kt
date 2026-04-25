package com.pync.intellij.network

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel

/**
 * Outbound connection manager.
 *
 * Each [sendMessage] call opens a short-lived blocking TCP connection, sends the frame,
 * and closes. This keeps the implementation simple and correct for the infrequent
 * secret-sharing use case; a persistent connection pool can be layered on later.
 */
class PyncClient(
    private val ownPublicKeyB64: String,
    private val messageHandler: MessageHandler,
    private val scope: CoroutineScope,
) {
    private val log = thisLogger()

    /**
     * Connects to [ip]:[port], sends a HELLO frame identifying this node, and returns
     * the open channel. Must be called off the EDT.
     */
    suspend fun connectToPeer(ip: String, port: Int): SocketChannel = withContext(Dispatchers.IO) {
        val channel = SocketChannel.open().also {
            it.configureBlocking(true)
            it.connect(InetSocketAddress(ip, port))
        }
        val hello = PyncMessage(
            type = MessageType.HELLO,
            senderId = ownPublicKeyB64,
            payload = ownPublicKeyB64,
            timestamp = System.currentTimeMillis(),
        )
        messageHandler.writeMessage(channel, hello)
        channel
    }

    /**
     * Delivers [msg] to [peer] over a fresh TCP connection.
     * Fire-and-forget: [IOException] is logged but not propagated to the caller.
     */
    fun sendMessage(peer: Peer, msg: PyncMessage) {
        scope.launch(Dispatchers.IO) {
            try {
                connectToPeer(peer.ip, peer.port).use { channel ->
                    messageHandler.writeMessage(channel, msg)
                }
            } catch (e: IOException) {
                log.warn(
                    "Could not deliver ${msg.type} to '${peer.displayName}' " +
                        "(${peer.ip}:${peer.port}): ${e.message}"
                )
            }
        }
    }
}
