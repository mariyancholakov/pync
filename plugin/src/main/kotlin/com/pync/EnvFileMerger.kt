package com.pync

object EnvFileMerger {

    fun merge(existing: String, incoming: Map<String, String>): String {
        val remaining = incoming.toMutableMap()
        val lines = existing.lines()
        val result = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.add(line)
                continue
            }
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) {
                result.add(line)
                continue
            }
            val key = trimmed.substring(0, eqIndex).trim()
            if (key in remaining) {
                result.add("$key=${remaining.remove(key)}")
            }
        }

        if (remaining.isNotEmpty()) {
            if (result.isNotEmpty() && result.last().isNotBlank()) {
                result.add("")
            }
            for ((key, value) in remaining) {
                result.add("$key=$value")
            }
        }

        val text = result.joinToString("\n")
        return if (text.endsWith("\n")) text else "$text\n"
    }
}
