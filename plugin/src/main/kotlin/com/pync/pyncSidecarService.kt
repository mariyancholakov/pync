package com.pync

import com.intellij.openapi.Disposable
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
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.APP)
class PyncSidecarService : Disposable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ready = CompletableDeferred<Unit>()

    private fun resolveSidecarPath(projectPath: String?): String {
        if (projectPath != null) {
            val projectCandidate = Path.of(projectPath, "sidecar", "index.js")
            if (projectCandidate.toFile().exists()) return projectCandidate.toString()
        }

        val pluginDir = Path.of(javaClass.protectionDomain.codeSource.location.toURI()).parent
        val candidate = pluginDir.resolve("../../sidecar/index.js").normalize()
        if (candidate.toFile().exists()) return candidate.toString()

        val cwdCandidate = Path.of(System.getProperty("user.dir"), "sidecar", "index.js")
        if (cwdCandidate.toFile().exists()) return cwdCandidate.toString()

        return Path.of(System.getProperty("user.dir"), "..", "sidecar", "index.js").normalize().toString()
    }

    fun start(projectPath: String? = null) {
        if (process?.isAlive == true) return
        scope.launch {
            try {
                val sidecarPath = resolveSidecarPath(projectPath)
                val nodePath = listOf(
                    "/usr/local/bin/node",
                    "/opt/homebrew/bin/node",
                    "C:\\Program Files\\nodejs\\node.exe",
                    System.getenv("NVM_SYMLINK")?.let { "$it\\node.exe" } ?: "",
                    "node"
                ).first { it.isNotEmpty() && (Path.of(it).toFile().exists() || it == "node") }
                val pb = ProcessBuilder(nodePath, sidecarPath)
                    .redirectErrorStream(false)
                pb.environment()["PATH"] = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
                val proc = pb.start()
                process = proc
                writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
                ready.complete(Unit)

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
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
                put("message", "Sidecar process exited")
            })
        }
    }

    fun sendCommand(json: String) {
        scope.launch {
            try {
                ready.await()
                writer?.let {
                    it.write(json + "\n")
                    it.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun addListener(cb: (JsonObject) -> Unit) {
        listeners.add(cb)
    }

    fun removeListener(cb: (JsonObject) -> Unit) {
        listeners.remove(cb)
    }

    fun isRunning(): Boolean {
        return try {
            process?.isAlive == true
        } catch (_: Exception) {
            false
        }
    }

    override fun dispose() {
        destroy()
    }

    fun destroy() {
        try {
            scope.cancel()
            process?.destroyForcibly()
            process = null
            writer = null
        } catch (_: Exception) {
        }
    }

    private fun notifyListeners(json: JsonObject) {
        for (listener in listeners) {
            try {
                listener(json)
            } catch (_: Exception) {
            }
        }
    }
}
