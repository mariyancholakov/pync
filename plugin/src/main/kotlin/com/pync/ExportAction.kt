package com.pync

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.io.File

class ExportAction {

    fun export(secrets: List<Pair<String, String>>, projectPath: String, project: Project? = null) {
        val content = secrets.joinToString("\n") { "${it.first}=${it.second}" } + "\n"
        File(projectPath, ".env").writeText(content)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pync")
            .createNotification("Pync", ".env exported with ${secrets.size} secrets", NotificationType.INFORMATION)
            .notify(project)
    }
}
