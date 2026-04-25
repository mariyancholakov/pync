package com.pync.intellij.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

interface PeerRegistryListener {
    fun onPeerJoined(peer: Peer)
    fun onPeerLeft(peerId: String)
    fun onPeerUpdated(peer: Peer)
}

/**
 * Thread-safe store of known peers. Updated by [MdnsDiscovery] and [MessageHandler];
 * observed by the UI layer via [PeerRegistryListener].
 */
class PeerRegistry {

    private val peers = ConcurrentHashMap<String, Peer>()
    private val listeners = CopyOnWriteArrayList<PeerRegistryListener>()

    fun onPeerDiscovered(peer: Peer) {
        val previous = peers.put(peer.id, peer)
        if (previous == null) {
            listeners.forEach { it.onPeerJoined(peer) }
        } else if (previous != peer) {
            listeners.forEach { it.onPeerUpdated(peer) }
        }
    }

    fun onPeerLost(peerId: String) {
        val peer = peers[peerId] ?: return
        peers[peerId] = peer.copy(isOnline = false)
        listeners.forEach { it.onPeerLeft(peerId) }
    }

    fun addListener(l: PeerRegistryListener) { listeners.add(l) }
    fun removeListener(l: PeerRegistryListener) { listeners.remove(l) }

    fun getPeer(id: String): Peer? = peers[id]
    fun getPeers(): List<Peer> = peers.values.toList()
    fun getOnlinePeers(): List<Peer> = peers.values.filter { it.isOnline }
}
