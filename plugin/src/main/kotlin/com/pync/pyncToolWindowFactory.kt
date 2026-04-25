package com.pync

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

class PyncToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val sidecarService = ApplicationManager.getApplication().getService(PyncSidecarService::class.java)

        val statusLabel = JLabel("● Disconnected")
        statusLabel.foreground = Color.RED

        val tableModel = DefaultTableModel(arrayOf("Key", "Value"), 0)
        val secretsTable = JBTable(tableModel)
        secretsTable.setShowGrid(false)

        val currentSecrets = mutableListOf<Pair<String, String>>()

        val addButton = JButton("Add")
        val editButton = JButton("Edit")
        val deleteButton = JButton("Delete")
        val exportButton = JButton("Export .env")

        editButton.isEnabled = false
        deleteButton.isEnabled = false

        secretsTable.selectionModel.addListSelectionListener {
            val hasSelection = secretsTable.selectedRow >= 0
            editButton.isEnabled = hasSelection
            deleteButton.isEnabled = hasSelection
        }

        addButton.addActionListener {
            val dialog = AddSecretDialog(project)
            if (dialog.showAndGet()) {
                val result = dialog.getResult() ?: return@addActionListener
                val json = buildJsonObject {
                    put("cmd", "set")
                    put("key", result.first)
                    put("value", result.second)
                }
                sidecarService.sendCommand(json.toString())
            }
        }

        editButton.addActionListener {
            val row = secretsTable.selectedRow
            if (row < 0) return@addActionListener
            val key = tableModel.getValueAt(row, 0) as String
            val value = tableModel.getValueAt(row, 1) as String
            val dialog = EditSecretDialog(project, key, value)
            if (dialog.showAndGet()) {
                val newValue = dialog.getResult() ?: return@addActionListener
                val json = buildJsonObject {
                    put("cmd", "set")
                    put("key", key)
                    put("value", newValue)
                }
                sidecarService.sendCommand(json.toString())
            }
        }

        deleteButton.addActionListener {
            val row = secretsTable.selectedRow
            if (row < 0) return@addActionListener
            val key = tableModel.getValueAt(row, 0) as String
            val json = buildJsonObject {
                put("cmd", "delete")
                put("key", key)
            }
            sidecarService.sendCommand(json.toString())
        }

        exportButton.addActionListener {
            val basePath = project.basePath ?: return@addActionListener
            ExportAction().export(currentSecrets, basePath, project)
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(addButton)
        buttonPanel.add(editButton)
        buttonPanel.add(deleteButton)
        buttonPanel.add(exportButton)

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(statusLabel, BorderLayout.NORTH)
        mainPanel.add(JScrollPane(secretsTable), BorderLayout.CENTER)
        mainPanel.add(buttonPanel, BorderLayout.SOUTH)

        fun updateTable(secrets: List<Pair<String, String>>) {
            currentSecrets.clear()
            currentSecrets.addAll(secrets)
            tableModel.setRowCount(0)
            for ((k, v) in secrets) {
                tableModel.addRow(arrayOf(k, v))
            }
        }

        fun parseSecrets(json: JsonObject): List<Pair<String, String>> {
            val arr = json["secrets"]?.jsonArray ?: return emptyList()
            return arr.map { item ->
                val obj = item.jsonObject
                Pair(
                    obj["key"]?.jsonPrimitive?.content ?: "",
                    obj["value"]?.jsonPrimitive?.content ?: ""
                )
            }
        }

        val listener: (JsonObject) -> Unit = { json ->
            val type = json["type"]?.jsonPrimitive?.content
            SwingUtilities.invokeLater {
                when (type) {
                    "ready" -> {
                        statusLabel.text = "● Syncing..."
                        statusLabel.foreground = Color.YELLOW
                    }
                    "list", "update" -> {
                        updateTable(parseSecrets(json))
                        statusLabel.text = "● Synced"
                        statusLabel.foreground = Color.GREEN
                    }
                    "peers" -> {}
                    "error" -> {
                        val message = json["message"]?.jsonPrimitive?.content ?: "Unknown error"
                        statusLabel.text = "● Error: $message"
                        statusLabel.foreground = Color.RED
                    }
                }
            }
        }

        sidecarService.addListener(listener)

        Disposer.register(toolWindow.disposable, Disposable {
            sidecarService.removeListener(listener)
            sidecarService.destroy()
        })

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
