package com.pync

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.CompoundBorder

class PyncToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val sidecarService = ApplicationManager.getApplication().getService(PyncSidecarService::class.java)

        var role = ""
        var topicKey = ""
        val secrets = mutableListOf<Pair<String, String>>()
<<<<<<< Updated upstream
        val revealedRows = mutableSetOf<Int>()
        val envWriter = WriteToEnvAction()
=======
>>>>>>> Stashed changes

        val rootPanel = JPanel(CardLayout())

        // =====================================================================
        // CONNECTION SCREEN — hero layout
        // =====================================================================
        val connectPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()

        val brandPanel = JPanel()
        brandPanel.layout = BoxLayout(brandPanel, BoxLayout.Y_AXIS)
        brandPanel.isOpaque = false

        val titleLabel = JBLabel("PYNC")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 28f)
        titleLabel.foreground = Color(0x00, 0xBC, 0xD4)
        titleLabel.alignmentX = Component.CENTER_ALIGNMENT

        val subtitleLabel = JBLabel("Peer-to-peer encrypted secrets")
        subtitleLabel.foreground = UIUtil.getLabelDisabledForeground()
        subtitleLabel.font = subtitleLabel.font.deriveFont(12f)
        subtitleLabel.alignmentX = Component.CENTER_ALIGNMENT

        val taglineLabel = JBLabel("No cloud. No server. AES-256-GCM encrypted.")
        taglineLabel.foreground = UIUtil.getLabelDisabledForeground()
        taglineLabel.font = taglineLabel.font.deriveFont(Font.ITALIC, 10f)
        taglineLabel.alignmentX = Component.CENTER_ALIGNMENT

        brandPanel.add(Box.createVerticalGlue())
        brandPanel.add(titleLabel)
        brandPanel.add(Box.createVerticalStrut(4))
        brandPanel.add(subtitleLabel)
        brandPanel.add(Box.createVerticalStrut(2))
        brandPanel.add(taglineLabel)
        brandPanel.add(Box.createVerticalStrut(24))

        val statusLabel = JBLabel("Not connected")
        statusLabel.icon = AllIcons.Nodes.EmptyNode
        statusLabel.foreground = UIUtil.getLabelDisabledForeground()
        statusLabel.alignmentX = Component.CENTER_ALIGNMENT
        brandPanel.add(statusLabel)
        brandPanel.add(Box.createVerticalStrut(20))

        val buttonsPanel = JPanel(GridLayout(1, 2, 12, 0))
        buttonsPanel.isOpaque = false
        buttonsPanel.maximumSize = Dimension(320, 36)

        val createBtn = JButton("Create Workspace")
        createBtn.icon = AllIcons.General.Add
        createBtn.putClientProperty("JButton.buttonType", "default")

        val joinBtn = JButton("Join Workspace")
        joinBtn.icon = AllIcons.Vcs.Fetch

        buttonsPanel.add(createBtn)
        buttonsPanel.add(joinBtn)
        buttonsPanel.alignmentX = Component.CENTER_ALIGNMENT
        brandPanel.add(buttonsPanel)
        brandPanel.add(Box.createVerticalGlue())

        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0; gbc.weighty = 1.0
        connectPanel.add(brandPanel, gbc)

        // =====================================================================
        // SECRETS SCREEN
        // =====================================================================
        val secretsToolPanel = SimpleToolWindowPanel(true, true)

        // --- Header bar ---
        val headerPanel = JPanel(BorderLayout(8, 0))
        headerPanel.border = JBUI.Borders.empty(6, 10, 6, 10)

        val syncDot = JBLabel()
        syncDot.icon = AllIcons.Actions.Refresh
        syncDot.text = "Syncing..."
        syncDot.font = syncDot.font.deriveFont(Font.BOLD, 11f)

        val peerChip = JBLabel("0 peers")
        peerChip.icon = AllIcons.Actions.GroupBy
        peerChip.font = peerChip.font.deriveFont(11f)
        peerChip.foreground = UIUtil.getLabelDisabledForeground()

        val topicChip = JBLabel("")
        topicChip.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        topicChip.foreground = UIUtil.getLabelDisabledForeground()
        topicChip.cursor = Cursor(Cursor.HAND_CURSOR)
        topicChip.toolTipText = "Click to copy workspace key"
        topicChip.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (topicKey.isNotEmpty()) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(topicKey), null)
                    Notifications.Bus.notify(
                        Notification("Pync", "Workspace key copied", NotificationType.INFORMATION), project
                    )
                }
            }
        })

        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        leftHeader.isOpaque = false
        leftHeader.add(syncDot)
        leftHeader.add(topicChip)

        headerPanel.add(leftHeader, BorderLayout.WEST)
        headerPanel.add(peerChip, BorderLayout.EAST)

        // --- Secrets list ---
        val listModel = DefaultListModel<Pair<String, String>>()
        val revealedKeys = mutableSetOf<String>()

        val secretList = JBList(listModel)
        secretList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        secretList.emptyText.text = "No secrets yet — click + to add one"
        secretList.fixedCellHeight = 64

        secretList.cellRenderer = ListCellRenderer<Pair<String, String>> { _, pair, index, isSelected, _ ->
            val (k, v) = pair
            val cell = JPanel(BorderLayout(10, 0))
            cell.border = CompoundBorder(
                @Suppress("DEPRECATION") JBUI.Borders.customLine(UIUtil.getSeparatorColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 12, 8, 12)
            )

            if (isSelected) {
                cell.background = UIUtil.getListSelectionBackground(true)
            } else {
                cell.background = if (index % 2 == 0) UIUtil.getListBackground() else UIUtil.getDecoratedRowColor()
            }

            val leftSide = JPanel(BorderLayout(6, 0))
            leftSide.isOpaque = false

            val iconLabel = JBLabel(AllIcons.Nodes.SecurityRole)
            leftSide.add(iconLabel, BorderLayout.WEST)

            val textPanel = JPanel()
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.isOpaque = false

            val keyLbl = JBLabel(k)
            keyLbl.font = keyLbl.font.deriveFont(Font.BOLD, 13f)
            if (isSelected) keyLbl.foreground = UIUtil.getListSelectionForeground(true)
            textPanel.add(keyLbl)

            val dots = minOf(v.length, 20).coerceAtLeast(8)
            val displayValue = if (revealedKeys.contains(k)) v else "•".repeat(dots)
            val valLbl = JBLabel(displayValue)
            valLbl.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            valLbl.foreground = if (isSelected) {
                val fg = UIUtil.getListSelectionForeground(true)
                Color(fg.red, fg.green, fg.blue, 180)
            } else {
                Color(0x00, 0xBC, 0xD4)
            }
            textPanel.add(valLbl)

            leftSide.add(textPanel, BorderLayout.CENTER)
            cell.add(leftSide, BorderLayout.CENTER)

            val lockLabel = JBLabel(
                if (revealedKeys.contains(k)) AllIcons.Actions.ToggleVisibility else AllIcons.Ide.HectorOn
            )
            lockLabel.toolTipText = if (revealedKeys.contains(k)) "Click to hide" else "Click to reveal"
            cell.add(lockLabel, BorderLayout.EAST)

            cell
        }

        secretList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = secretList.locationToIndex(e.point)
                if (idx < 0) return
                val cellBounds = secretList.getCellBounds(idx, idx) ?: return
                val relativeX = e.x - cellBounds.x
                if (relativeX > cellBounds.width - 40) {
                    val key = listModel.getElementAt(idx).first
                    if (revealedKeys.contains(key)) revealedKeys.remove(key) else revealedKeys.add(key)
                    secretList.repaint()
                }
            }
        })

        val scrollPane = JBScrollPane(secretList)

        // --- Toolbar ---
        val actionGroup = DefaultActionGroup()

        actionGroup.add(object : AnAction("Add Secret", "Add a new secret", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val dialog = AddSecretDialog(project)
                if (dialog.showAndGet()) {
                    val (key, value) = dialog.getResult() ?: return
                    sidecarService.sendCommand(buildJsonObject {
                        put("cmd", "set"); put("key", key); put("value", value)
                    }.toString())
                }
            }
        })

        actionGroup.add(object : AnAction("Edit Secret", "Edit the selected secret", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                val sel = secretList.selectedValue ?: return
                val dialog = EditSecretDialog(project, sel.first, sel.second)
                if (dialog.showAndGet()) {
                    val (newKey, newValue) = dialog.getResult() ?: return
                    if (newKey != dialog.originalKey) {
                        sidecarService.sendCommand(buildJsonObject {
                            put("cmd", "delete"); put("key", dialog.originalKey)
                        }.toString())
                    }
                    sidecarService.sendCommand(buildJsonObject {
                        put("cmd", "set"); put("key", newKey); put("value", newValue)
                    }.toString())
                }
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = secretList.selectedValue != null }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })

        actionGroup.add(object : AnAction("Delete Secret", "Delete the selected secret", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                val sel = secretList.selectedValue ?: return
                sidecarService.sendCommand(buildJsonObject {
                    put("cmd", "delete"); put("key", sel.first)
                }.toString())
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = secretList.selectedValue != null }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })

        actionGroup.addSeparator()

        actionGroup.add(object : AnAction("Copy Value", "Copy the selected secret value", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val sel = secretList.selectedValue ?: return
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sel.second), null)
                Notifications.Bus.notify(
                    Notification("Pync", "Copied ${sel.first}", NotificationType.INFORMATION), project
                )
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = secretList.selectedValue != null }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })

        actionGroup.add(object : AnAction("Export .env", "Export all secrets to .env file", AllIcons.Actions.Download) {
            override fun actionPerformed(e: AnActionEvent) {
                val all = (0 until listModel.size()).map { listModel.getElementAt(it) }
                if (all.isNotEmpty()) ExportAction().export(all, project.basePath ?: ".", project)
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = listModel.size() > 0 }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })

        actionGroup.addSeparator()

        actionGroup.add(object : AnAction("Leave Workspace", "Disconnect and clear local data", AllIcons.Actions.Exit) {
            override fun actionPerformed(e: AnActionEvent) {
                sidecarService.destroy()
                val dataDir = java.io.File(project.basePath ?: ".", "data")
                if (dataDir.exists()) dataDir.deleteRecursively()
                secrets.clear()
                listModel.clear()
                revealedKeys.clear()
                role = ""
                topicKey = ""
                SwingUtilities.invokeLater {
                    statusLabel.text = "Not connected"
                    statusLabel.icon = AllIcons.Nodes.EmptyNode
                    statusLabel.foreground = UIUtil.getLabelDisabledForeground()
                    (rootPanel.layout as CardLayout).show(rootPanel, "connect")
                }
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("PyncToolbar", actionGroup, true)
        toolbar.targetComponent = secretsToolPanel

        val topSection = JPanel(BorderLayout())
        topSection.add(headerPanel, BorderLayout.NORTH)
        topSection.add(toolbar.component, BorderLayout.SOUTH)

        secretsToolPanel.setContent(scrollPane)
        secretsToolPanel.setToolbar(topSection)

        // =====================================================================
        // CARD LAYOUT
        // =====================================================================
        rootPanel.add(connectPanel, "connect")
        rootPanel.add(secretsToolPanel, "secrets")
        (rootPanel.layout as CardLayout).show(rootPanel, "connect")

        fun refreshList(newSecrets: List<Pair<String, String>>) {
            secrets.clear()
            secrets.addAll(newSecrets)
            listModel.clear()
            for (s in newSecrets) listModel.addElement(s)
        }

        // =====================================================================
        // BUTTON ACTIONS
        // =====================================================================
        createBtn.addActionListener {
            val dialog = CreateWorkspaceDialog(project)
            if (dialog.showAndGet()) {
                val (workspace, passphrase) = dialog.getResult() ?: return@addActionListener
                sidecarService.start(project.basePath)
                SwingUtilities.invokeLater {
                    statusLabel.text = "Connecting..."
                    statusLabel.icon = AllIcons.Actions.Refresh
                    statusLabel.foreground = Color(0xFF, 0x98, 0x00)
                }
                sidecarService.sendCommand(buildJsonObject {
                    put("cmd", "create"); put("workspace", workspace); put("passphrase", passphrase)
                }.toString())
            }
        }

        joinBtn.addActionListener {
            val dialog = JoinWorkspaceDialog(project)
            if (dialog.showAndGet()) {
                val (tk, passphrase) = dialog.getResult() ?: return@addActionListener
                sidecarService.start(project.basePath)
                SwingUtilities.invokeLater {
                    statusLabel.text = "Connecting..."
                    statusLabel.icon = AllIcons.Actions.Refresh
                    statusLabel.foreground = Color(0xFF, 0x98, 0x00)
                }
                sidecarService.sendCommand(buildJsonObject {
                    put("cmd", "join"); put("topicKey", tk); put("passphrase", passphrase)
                }.toString())
            }
        }

<<<<<<< Updated upstream
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
=======
        // =====================================================================
        // SIDECAR LISTENER
        // =====================================================================
>>>>>>> Stashed changes
        sidecarService.addListener { json ->
            val type = json["type"]?.jsonPrimitive?.content ?: return@addListener

            when (type) {
                "ready" -> {
                    role = json["role"]?.jsonPrimitive?.content ?: ""
                    topicKey = json["topicKey"]?.jsonPrimitive?.content ?: ""
                    SwingUtilities.invokeLater {
                        syncDot.icon = AllIcons.Actions.Refresh
                        syncDot.text = "Syncing..."
                        syncDot.foreground = Color(0xFF, 0x98, 0x00)
                        topicChip.text = topicKey.take(12) + "..."
                        topicChip.toolTipText = "Key: $topicKey  (click to copy)"
                        (rootPanel.layout as CardLayout).show(rootPanel, "secrets")
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
<<<<<<< Updated upstream
                        syncStatusLabel.text = "● Synced"
                        syncStatusLabel.foreground = Color(0x2E, 0x7D, 0x32)
                        refreshTable(parsed)
                        envWriter.autoSync(project, parsed)
=======
                        syncDot.icon = AllIcons.General.InspectionsOK
                        syncDot.text = "Synced"
                        syncDot.foreground = Color(0x4C, 0xAF, 0x50)
                        refreshList(parsed)
>>>>>>> Stashed changes
                    }
                }

                "peers" -> {
                    val count = json["count"]?.jsonPrimitive?.int ?: 0
                    SwingUtilities.invokeLater {
                        peerChip.text = "$count peer${if (count != 1) "s" else ""}"
                        peerChip.icon = if (count > 0) AllIcons.Actions.GroupBy else AllIcons.General.Warning
                    }
                }

                "error" -> {
                    val message = json["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    SwingUtilities.invokeLater {
                        syncDot.icon = AllIcons.General.Error
                        syncDot.text = message
                        syncDot.foreground = Color(0xF4, 0x43, 0x36)
                        statusLabel.text = "Error: $message"
                        statusLabel.icon = AllIcons.General.Error
                        statusLabel.foreground = Color(0xF4, 0x43, 0x36)
                    }
                }
            }
        }

        val content = ContentFactory.getInstance().createContent(rootPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
