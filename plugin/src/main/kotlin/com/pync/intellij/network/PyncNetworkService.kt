package com.pync.intellij.network

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.pync.intellij.crypto.CryptoManager
import com.pync.intellij.crypto.KeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level service that owns the TCP server, mDNS stack, and peer registry.
 *
 * Lifecycle:
 *  - [start] is called once from [PyncStartupActivity] when any project opens.
 *  - [dispose] is called by the IntelliJ Platform on IDE shutdown.
 *
 * All heavy I/O runs on [Dispatchers.IO]; this service never blocks the EDT.
 */
@Service(Service.Level.APP)
class PyncNetworkService : Disposable {

    private val log = thisLogger()
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private val started = AtomicBoolean(false)

    /** Live peer map — safe to read from any thread; updated by mDNS and connection events. */
    val registry = PeerRegistry()

    private var server: PyncServer? = null
    private var discovery: MdnsDiscovery? = null
    private var _client: PyncClient? = null

    companion object {
        fun getInstance(): PyncNetworkService =
            ApplicationManager.getApplication().getService(PyncNetworkService::class.java)
    }

    /**
     * Starts the server and mDNS subsystems. Idempotent — subsequent calls are no-ops.
     * Returns immediately; the actual startup runs on [Dispatchers.IO].
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                val keyStore = KeyStore.getInstance()
                val publicKeyBytes = keyStore.getPublicKey()
                val publicKeyB64 = CryptoManager.b64Encoder.encodeToString(publicKeyBytes)
                val displayName = System.getProperty("user.name")?.takeIf { it.isNotEmpty() }
                    ?: "pync-user"

                val handler = MessageHandler(registry, publicKeyB64)

                val pyncServer = PyncServer(handler)
                val port = pyncServer.start()
                server = pyncServer

                _client = PyncClient(publicKeyB64, handler, scope)

                val mdns = MdnsDiscovery(registry, publicKeyB64)
                mdns.start(displayName, publicKeyB64, port)
                discovery = mdns
            } catch (e: Exception) {
                log.error("pync network startup failed", e)
                started.set(false)  // allow retry
            }
        }
    }

    /**
     * Sends [msg] to [peer] over a fresh TCP connection on a background coroutine.
     * Fire-and-forget; errors are logged internally.
     */
    fun sendToPeer(peer: Peer, msg: PyncMessage) {
        val client = _client
        if (client == null) {
            log.warn("sendToPeer called before network service has finished starting")
            return
        }
        client.sendMessage(peer, msg)
    }

    override fun dispose() {
        discovery?.stop()
        server?.stop()
        supervisorJob.cancel()
    }
}
