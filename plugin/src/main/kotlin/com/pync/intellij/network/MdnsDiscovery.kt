package com.pync.intellij.network

import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.util.Base64
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Advertises this node on the LAN and discovers other pync nodes via mDNS.
 *
 * TXT record keys:
 *   "pk"   — base64url-encoded X25519 public key (used as peer ID)
 *   "name" — human-readable display name
 *
 * Self-discovery is suppressed by comparing the resolved public key against [ownPublicKeyB64].
 */
class MdnsDiscovery(
    private val registry: PeerRegistry,
    private val ownPublicKeyB64: String,
) {
    private val log = thisLogger()
    private var jmdns: JmDNS? = null

    companion object {
        private const val SERVICE_TYPE = "_pync._tcp.local."
        private const val TXT_PK = "pk"
        private const val TXT_NAME = "name"
        private const val MAX_INSTANCE_NAME_LEN = 48
    }

    /**
     * Registers this node as [SERVICE_TYPE] and begins listening for peers.
     * Blocks briefly on JmDNS.create(); call from a background thread.
     */
    fun start(displayName: String, publicKeyB64: String, port: Int) {
        try {
            val instance = JmDNS.create()
            jmdns = instance

            // Service instance names must be ≤ 63 chars and DNS-safe.
            val safeName = displayName
                .replace(Regex("[^a-zA-Z0-9_-]"), "-")
                .take(MAX_INSTANCE_NAME_LEN - 9)   // leave room for "-XXXXXXXX" suffix
                .ifEmpty { "pync-user" }
            val instanceName = "$safeName-${publicKeyB64.take(8)}"

            val props = mapOf(TXT_PK to publicKeyB64, TXT_NAME to displayName)
            val serviceInfo = ServiceInfo.create(SERVICE_TYPE, instanceName, port, 0, 0, props)
            instance.registerService(serviceInfo)
            log.info("pync mDNS registered as '$instanceName' on port $port")

            instance.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    // Request full resolution so serviceResolved fires with TXT + address info.
                    instance.requestServiceInfo(event.type, event.name, /* persistent = */ true)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    val peerId = event.info?.getPropertyString(TXT_PK) ?: return
                    if (peerId == ownPublicKeyB64) return
                    registry.onPeerLost(peerId)
                    log.info("Peer lost: ${event.name}")
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info ?: return
                    val peerId = info.getPropertyString(TXT_PK) ?: run {
                        log.debug("Resolved service '${event.name}' has no 'pk' TXT record, ignoring")
                        return
                    }
                    if (peerId == ownPublicKeyB64) return  // skip self

                    val addresses = info.inet4Addresses
                    if (addresses.isEmpty()) {
                        log.debug("Peer '${event.name}' has no IPv4 address, skipping")
                        return
                    }
                    val ip = addresses[0].hostAddress ?: return
                    val peerName = info.getPropertyString(TXT_NAME)?.takeIf { it.isNotEmpty() }
                        ?: event.name

                    val publicKeyBytes = try {
                        Base64.getUrlDecoder().decode(peerId)
                    } catch (e: IllegalArgumentException) {
                        log.warn("Peer '${event.name}' advertised an invalid base64 public key")
                        return
                    }

                    val peer = Peer(
                        id = peerId,
                        displayName = peerName,
                        publicKeyBytes = publicKeyBytes,
                        ip = ip,
                        port = info.port,
                        isOnline = true,
                    )
                    registry.onPeerDiscovered(peer)
                    log.info("Discovered peer '$peerName' at $ip:${info.port}")
                }
            })
        } catch (e: IOException) {
            log.error("mDNS initialization failed — peer discovery will be unavailable", e)
        }
    }

    fun stop() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: IOException) {
            log.warn("Error while closing JmDNS", e)
        } finally {
            jmdns = null
        }
    }
}
