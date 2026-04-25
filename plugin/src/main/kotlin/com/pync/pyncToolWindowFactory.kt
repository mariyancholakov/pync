package com.pync

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlinx.serialization.json.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class PyncToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val sidecarService = ApplicationManager.getApplication().getService(PyncSidecarService::class.java)

        var role = ""
        var topicKey = ""
        val secrets = mutableListOf<Pair<String, String>>()
        val revealedRows = mutableSetOf<Int>()
        val envWriter = WriteToEnvAction()

        val rootPanel = JPanel(CardLayout())

        // ========== CONNECTION PANEL ==========
        val connectPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(6, 6, 6, 6)
            fill = GridBagConstraints.HORIZONTAL
        }

        val statusLabel = JLabel("● Disconnected")
        statusLabel.foreground = Color.RED

        val createBtn = JButton("Create Workspace")
        val joinBtn = JButton("Join Workspace")

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        connectPanel.add(statusLabel, gbc)
        gbc.gridy = 1; gbc.gridwidth = 1
        connectPanel.add(createBtn, gbc)
        gbc.gridx = 1
        connectPanel.add(joinBtn, gbc)

        // ========== SECRETS PANEL ==========
        val secretsPanel = JPanel(BorderLayout(0, 4))
        secretsPanel.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)

        val topBar = JPanel(BorderLayout(8, 0))
        val syncStatusLabel = JLabel("● Syncing...")
        syncStatusLabel.foreground = Color(0xED, 0x6C, 0x02)
        val peerLabel = JLabel("0 peers")
        val topicBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val topicDisplay = JLabel("")
        val copyBtn = JButton("Copy")
        topicBar.add(topicDisplay)
        topicBar.add(copyBtn)

        val topLeft = JPanel()
        topLeft.layout = BoxLayout(topLeft, BoxLayout.Y_AXIS)
        topLeft.add(syncStatusLabel)
        topLeft.add(topicBar)

        topBar.add(topLeft, BorderLayout.WEST)
        topBar.add(peerLabel, BorderLayout.EAST)

        val tableModel = object : DefaultTableModel(arrayOf("KEY", "VALUE", "ACTIONS"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = column == 2
        }
        val table = JTable(tableModel)
        table.rowHeight = 32

        table.columnModel.getColumn(0).cellRenderer = DefaultTableCellRenderer()

        table.columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val display = if (revealedRows.contains(row)) value?.toString() ?: "" else "••••••••"
                val comp = super.getTableCellRendererComponent(table, display, isSelected, hasFocus, row, column)
                border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
                return comp
            }
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (col == 1 && row >= 0) {
                    if (revealedRows.contains(row)) revealedRows.remove(row) else revealedRows.add(row)
                    tableModel.fireTableRowsUpdated(row, row)
                }
            }
        })

        val actionsRenderer = TableCellRenderer { _, _, _, _, _, _ ->
            val p = JPanel(FlowLayout(FlowLayout.CENTER, 2, 0))
            p.add(JButton("Edit"))
            p.add(JButton("Delete"))
            p
        }
        table.columnModel.getColumn(2).cellRenderer = actionsRenderer

        val actionsEditor = object : AbstractCellEditor(), TableCellEditor {
            private val panel = JPanel(FlowLayout(FlowLayout.CENTER, 2, 0))
            private val editBtn = JButton("Edit")
            private val deleteBtn = JButton("Delete")
            private var currentRow = -1

            init {
                editBtn.addActionListener {
                    fireEditingStopped()
                    if (currentRow in secrets.indices) {
                        val (key, value) = secrets[currentRow]
                        val dialog = EditSecretDialog(project, key, value)
                        if (dialog.showAndGet()) {
                            val (newKey, newValue) = dialog.getResult() ?: return@addActionListener
                            if (newKey != dialog.originalKey) {
                                val delCmd = buildJsonObject {
                                    put("cmd", "delete")
                                    put("key", dialog.originalKey)
                                }
                                sidecarService.sendCommand(delCmd.toString())
                            }
                            val cmd = buildJsonObject {
                                put("cmd", "set")
                                put("key", newKey)
                                put("value", newValue)
                            }
                            sidecarService.sendCommand(cmd.toString())
                        }
                    }
                }
                deleteBtn.addActionListener {
                    fireEditingStopped()
                    if (currentRow in secrets.indices) {
                        val key = secrets[currentRow].first
                        val cmd = buildJsonObject {
                            put("cmd", "delete")
                            put("key", key)
                        }
                        sidecarService.sendCommand(cmd.toString())
                    }
                }
                panel.add(editBtn)
                panel.add(deleteBtn)
            }

            override fun getTableCellEditorComponent(
                table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
            ): Component {
                currentRow = row
                editBtn.isVisible = true
                deleteBtn.isVisible = true
                return panel
            }

            override fun getCellEditorValue(): Any = ""
        }
        table.columnModel.getColumn(2).cellEditor = actionsEditor

        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        val addSecretBtn = JButton("Add Secret")
        val deleteSecretBtn = JButton("Delete Secret")
        val leaveBtn = JButton("Leave")
        addSecretBtn.isVisible = true
        deleteSecretBtn.isEnabled = false
        bottomPanel.add(addSecretBtn)
        bottomPanel.add(deleteSecretBtn)
        bottomPanel.add(leaveBtn)

        secretsPanel.add(topBar, BorderLayout.NORTH)
        secretsPanel.add(JScrollPane(table), BorderLayout.CENTER)
        secretsPanel.add(bottomPanel, BorderLayout.SOUTH)

        // ========== CARD LAYOUT ==========
        rootPanel.add(connectPanel, "connect")
        rootPanel.add(secretsPanel, "secrets")
        (rootPanel.layout as CardLayout).show(rootPanel, "connect")

        // ========== HELPERS ==========
        fun refreshTable(newSecrets: List<Pair<String, String>>) {
            secrets.clear()
            secrets.addAll(newSecrets)
            revealedRows.clear()
            tableModel.setRowCount(0)
            for ((key, value) in secrets) {
                tableModel.addRow(arrayOf(key, value, ""))
            }
        }

        fun showSecretsPanel() {
            (rootPanel.layout as CardLayout).show(rootPanel, "secrets")
        }

        // ========== BUTTON ACTIONS ==========
        createBtn.addActionListener {
            val dialog = CreateWorkspaceDialog(project)
            if (dialog.showAndGet()) {
                val (workspace, passphrase) = dialog.getResult() ?: return@addActionListener
                sidecarService.start(project.basePath)
                SwingUtilities.invokeLater {
                    statusLabel.text = "● Syncing..."
                    statusLabel.foreground = Color(0xED, 0x6C, 0x02)
                }
                val cmd = buildJsonObject {
                    put("cmd", "create")
                    put("workspace", workspace)
                    put("passphrase", passphrase)
                }
                sidecarService.sendCommand(cmd.toString())
            }
        }

        joinBtn.addActionListener {
            val dialog = JoinWorkspaceDialog(project)
            if (dialog.showAndGet()) {
                val (tk, passphrase) = dialog.getResult() ?: return@addActionListener
                sidecarService.start(project.basePath)
                SwingUtilities.invokeLater {
                    statusLabel.text = "● Syncing..."
                    statusLabel.foreground = Color(0xED, 0x6C, 0x02)
                }
                val cmd = buildJsonObject {
                    put("cmd", "join")
                    put("topicKey", tk)
                    put("passphrase", passphrase)
                }
                sidecarService.sendCommand(cmd.toString())
            }
        }

        copyBtn.addActionListener {
            val sel = StringSelection(topicKey)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
        }

        table.selectionModel.addListSelectionListener {
            deleteSecretBtn.isEnabled = table.selectedRow >= 0
        }

        deleteSecretBtn.addActionListener {
            val row = table.selectedRow
            if (row >= 0 && row < secrets.size) {
                val key = secrets[row].first
                val cmd = buildJsonObject {
                    put("cmd", "delete")
                    put("key", key)
                }
                sidecarService.sendCommand(cmd.toString())
            }
        }

        addSecretBtn.addActionListener {
            val dialog = AddSecretDialog(project)
            if (dialog.showAndGet()) {
                val (key, value) = dialog.getResult() ?: return@addActionListener
                val cmd = buildJsonObject {
                    put("cmd", "set")
                    put("key", key)
                    put("value", value)
                }
                sidecarService.sendCommand(cmd.toString())
            }
        }

        leaveBtn.addActionListener {
            sidecarService.destroy()
            envWriter.resetRemembered()
            val dataDir = java.io.File(project.basePath ?: ".", "data")
            if (dataDir.exists()) dataDir.deleteRecursively()
            secrets.clear()
            tableModel.setRowCount(0)
            role = ""
            topicKey = ""
            SwingUtilities.invokeLater {
                statusLabel.text = "● Disconnected"
                statusLabel.foreground = Color.RED
                (rootPanel.layout as CardLayout).show(rootPanel, "connect")
            }
        }

        // ========== SIDECAR LISTENER ==========
        sidecarService.addListener { json ->
            val type = json["type"]?.jsonPrimitive?.content ?: return@addListener

            when (type) {
                "ready" -> {
                    role = json["role"]?.jsonPrimitive?.content ?: ""
                    topicKey = json["topicKey"]?.jsonPrimitive?.content ?: ""
                    SwingUtilities.invokeLater {
                        syncStatusLabel.text = "● Syncing..."
                        syncStatusLabel.foreground = Color(0xED, 0x6C, 0x02)
                        topicDisplay.text = "Topic: ${topicKey.take(16)}..."
                        addSecretBtn.isVisible = true
                        showSecretsPanel()
                    }
                }

                "list", "update" -> {
                    val items = json["secrets"]?.jsonArray ?: return@addListener
                    val parsed = items.map { entry ->
                        val obj = entry.jsonObject
                        val k = obj["key"]?.jsonPrimitive?.content ?: ""
                        val v = obj["value"]?.jsonPrimitive?.content ?: ""
                        k to v
                    }
                    SwingUtilities.invokeLater {
                        syncStatusLabel.text = "● Synced"
                        syncStatusLabel.foreground = Color(0x2E, 0x7D, 0x32)
                        refreshTable(parsed)
                        envWriter.autoSync(project, parsed)
                    }
                }

                "peers" -> {
                    val count = json["count"]?.jsonPrimitive?.int ?: 0
                    SwingUtilities.invokeLater {
                        peerLabel.text = "$count peer${if (count != 1) "s" else ""}"
                    }
                }

                "error" -> {
                    val message = json["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    SwingUtilities.invokeLater {
                        statusLabel.text = "● Error: $message"
                        statusLabel.foreground = Color.RED
                        syncStatusLabel.text = "● Error: $message"
                        syncStatusLabel.foreground = Color.RED
                    }
                }
            }
        }

        val content = ContentFactory.getInstance().createContent(rootPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
