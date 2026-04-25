package com.pync

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

class ExportAction {

    fun export(secrets: List<Pair<String, String>>, projectPath: String, project: Project? = null) {
        val root = File(projectPath)
        val envFiles = root.walk().filter { it.name == ".env" && !it.path.contains("node_modules") }.toList()

        val targetDir = when {
            envFiles.size > 1 -> {
                val dirs = envFiles.map { it.parentFile }.distinctBy { it.absolutePath }
                val dialog = ChooseEnvFolderDialog(project, dirs, root)
                if (dialog.showAndGet()) dialog.getSelectedDir() else null
            }
            else -> root
        } ?: return

        val content = secrets.joinToString("\n") { "${it.first}=${it.second}" } + "\n"
        File(targetDir, ".env").writeText(content)

        val relative = targetDir.toRelativeString(root).ifEmpty { "." }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pync")
            .createNotification("Pync", ".env exported to $relative/ with ${secrets.size} secrets", NotificationType.INFORMATION)
            .notify(project)
    }

    private class ChooseEnvFolderDialog(
        project: Project?,
        private val dirs: List<File>,
        private val root: File
    ) : DialogWrapper(project) {

        private val listModel = DefaultListModel<String>()
        private val dirList = JBList(listModel)
        private var selectedDir: File? = null

        init {
            title = "Export .env"
            dirs.forEach { listModel.addElement(it.toRelativeString(root).ifEmpty { "." }) }
            dirList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            dirList.selectedIndex = 0
            selectedDir = dirs.firstOrNull()
            dirList.addListSelectionListener(object : ListSelectionListener {
                override fun valueChanged(e: ListSelectionEvent) {
                    val idx = dirList.selectedIndex
                    selectedDir = if (idx >= 0) dirs[idx] else null
                    isOKActionEnabled = selectedDir != null
                }
            })
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, 8))
            panel.preferredSize = Dimension(400, 250)
            panel.border = JBUI.Borders.empty(8)

            val label = JBLabel("Multiple .env files found — choose a target folder:")
            label.font = label.font.deriveFont(Font.BOLD, 13f)
            panel.add(label, BorderLayout.NORTH)

            dirList.emptyText.text = "No folders found"
            dirList.fixedCellHeight = 28
            panel.add(JBScrollPane(dirList), BorderLayout.CENTER)

            return panel
        }

        fun getSelectedDir(): File? = selectedDir
    }
}
