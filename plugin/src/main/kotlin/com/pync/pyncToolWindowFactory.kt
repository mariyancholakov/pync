package com.pync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class PyncToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel()
        val statusLabel = JLabel("● Disconnected")
        panel.add(statusLabel)

        val sidecarService = ApplicationManager.getApplication().getService(PyncSidecarService::class.java)

        sidecarService.addListener { json ->
            val type = json["type"]?.jsonPrimitive?.content
            if (type == "error") {
                val message = json["message"]?.jsonPrimitive?.content ?: "Unknown error"
                SwingUtilities.invokeLater {
                    statusLabel.text = "● Error: $message"
                    statusLabel.foreground = Color.RED
                }
            }
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
