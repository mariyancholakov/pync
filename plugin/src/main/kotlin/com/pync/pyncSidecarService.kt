package com.pync

import com.intellij.openapi.components.Service
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

@Service(Service.Level.APP)
class PyncSidecarService {

    val SIDECAR_PATH: String = System.getProperty("user.dir") + "/../sidecar/index.js"

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val listeners = mutableListOf<(JsonObject) -> Unit>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        try {
            process = ProcessBuilder("node", SIDECAR_PATH)
                .redirectErrorStream(true)
                .start()

            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))

            scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try {
                            val json = Json.decodeFromString<JsonObject>(line!!)
                            notifyListeners(json)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                notifyListeners(buildJsonObject {
                    put("type", "error")
                    put("message", "Sidecar process died")
                })
            }
        } catch (_: Exception) {
        }
    }

    fun sendCommand(json: String) {
        try {
            writer?.write(json + "\n")
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    fun addListener(cb: (JsonObject) -> Unit) {
        synchronized(listeners) {
            listeners.add(cb)
        }
    }

    fun removeListener(cb: (JsonObject) -> Unit) {
        synchronized(listeners) {
            listeners.remove(cb)
        }
    }

    fun isRunning(): Boolean {
        return try {
            process?.isAlive == true
        } catch (_: Exception) {
            false
        }
    }

    fun destroy() {
        try {
            scope.cancel()
            process?.destroy()
            process = null
            writer = null
        } catch (_: Exception) {
        }
    }

    private fun notifyListeners(json: JsonObject) {
        val snapshot: List<(JsonObject) -> Unit>
        synchronized(listeners) {
            snapshot = listeners.toList()
        }
        for (listener in snapshot) {
            try {
                listener(json)
            } catch (_: Exception) {
            }
        }
    }
}
