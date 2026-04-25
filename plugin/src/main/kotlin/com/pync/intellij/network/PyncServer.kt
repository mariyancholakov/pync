package com.pync.intellij.network

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ServerSocketChannel

/**
 * Listens on an OS-assigned TCP port and dispatches each accepted connection to
 * [MessageHandler.handle] on a dedicated [Dispatchers.IO] coroutine.
 *
 * Shutdown: [stop] closes the [ServerSocketChannel], which unblocks the pending
 * [ServerSocketChannel.accept] call and causes it to throw [AsynchronousCloseException],
 * terminating the accept loop cleanly.
 */
class PyncServer(
    private val messageHandler: MessageHandler,
) : Disposable {

    private val log = thisLogger()
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var serverChannel: ServerSocketChannel? = null

    /**
     * Binds to port 0 (OS-assigned), starts the accept loop, and returns the bound port.
     * Safe to call once; subsequent calls are ignored.
     */
    fun start(): Int {
        check(serverChannel == null) { "PyncServer already started" }

        val channel = ServerSocketChannel.open().also {
            it.configureBlocking(true)
            it.bind(InetSocketAddress(0))
            serverChannel = it
        }
        val port = (channel.localAddress as InetSocketAddress).port

        scope.launch {
            while (isActive) {
                try {
                    val client = channel.accept()
                    client.configureBlocking(true)
                    // Each connection gets its own child coroutine; a crash in one
                    // does not affect the accept loop thanks to SupervisorJob.
                    launch { messageHandler.handle(client) }
                } catch (_: AsynchronousCloseException) {
                    break  // serverChannel.close() called from stop()
                } catch (e: IOException) {
                    if (isActive) log.warn("Error accepting connection", e)
                }
            }
        }

        log.info("pync TCP server listening on port $port")
        return port
    }

    fun stop() {
        serverChannel?.close()   // unblocks accept(), triggers AsynchronousCloseException
        serverChannel = null
        supervisorJob.cancel()
    }

    override fun dispose() = stop()
}
