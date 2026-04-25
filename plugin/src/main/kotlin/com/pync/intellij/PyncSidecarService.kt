package com.pync.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger

@Service(Service.Level.APP)
class PyncSidecarService {

    private val log = thisLogger()

    private var sidecarProcess: Process? = null

    fun start() {
        if (sidecarProcess?.isAlive == true) return
        log.info("Starting pync sidecar")
        // TODO: launch the pync CLI sidecar process and connect over local IPC
    }

    fun stop() {
        sidecarProcess?.destroy()
        sidecarProcess = null
        log.info("pync sidecar stopped")
    }

    fun isRunning(): Boolean = sidecarProcess?.isAlive == true
}
