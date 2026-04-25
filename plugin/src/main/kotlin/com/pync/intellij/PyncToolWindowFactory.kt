package com.pync.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel
import java.awt.BorderLayout

class PyncToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val sidecar = ApplicationManager.getApplication().getService(PyncSidecarService::class.java)
        sidecar.start()

        val panel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(JBLabel("pync — peer-to-peer secret sharing")), BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
