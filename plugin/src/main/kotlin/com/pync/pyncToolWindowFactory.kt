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

class PyncToolWindowFactory : ToolWindowFactory {

    private class FadePanel : JPanel(CardLayout()) {
        private var fadeAlpha = 1.0f
        private var fadeTimer: Timer? = null

        override fun paintChildren(g: Graphics) {
            if (fadeAlpha < 1.0f) {
                val g2 = g.create() as Graphics2D
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fadeAlpha)
                super.paintChildren(g2)
                g2.dispose()
            } else {
                super.paintChildren(g)
            }
        }

        fun showCard(name: String) {
            fadeTimer?.stop()
            fadeAlpha = 0.0f
            (layout as CardLayout).show(this, name)
            fadeTimer = Timer(16) {
                fadeAlpha = (fadeAlpha + 0.08f).coerceAtMost(1.0f)
                repaint()
                if (fadeAlpha >= 1.0f) fadeTimer?.stop()
            }
            fadeTimer!!.start()
        }
    }

    private class PulseLabel(text: String = "") : JBLabel(text) {
        private var pulseAlpha = 1.0f
        private var pulseDir = -1
        private var pulseTimer: Timer? = null

        fun startPulse() {
            stopPulse()
            pulseTimer = Timer(50) {
                pulseAlpha += pulseDir * 0.04f
                if (pulseAlpha <= 0.4f) { pulseAlpha = 0.4f; pulseDir = 1 }
                if (pulseAlpha >= 1.0f) { pulseAlpha = 1.0f; pulseDir = -1 }
                repaint()
            }
            pulseTimer!!.start()
        }

        fun stopPulse() {
            pulseTimer?.stop()
            pulseAlpha = 1.0f
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pulseAlpha)
            super.paintComponent(g2)
            g2.dispose()
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val sidecarService = ApplicationManager.getApplication().getService(PyncSidecarService::class.java)

        var role = ""
        var topicKey = ""
        val secrets = mutableListOf<Pair<String, String>>()
        val envWriter = WriteToEnvAction()

        val rootPanel = FadePanel()

        // =====================================================================
        // CONNECTION SCREEN — hero layout
        // =====================================================================
        val connectPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()

        val brandPanel = JPanel()
        brandPanel.layout = BoxLayout(brandPanel, BoxLayout.Y_AXIS)
        brandPanel.isOpaque = false

        val titleLabel = JBLabel("PYNC")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 36f)
        titleLabel.foreground = Color(0x00, 0xBC, 0xD4)
        titleLabel.alignmentX = Component.CENTER_ALIGNMENT

        val subtitleLabel = JBLabel("Peer-to-peer encrypted secrets")
        subtitleLabel.foreground = UIUtil.getLabelDisabledForeground()
        subtitleLabel.font = subtitleLabel.font.deriveFont(14f)
        subtitleLabel.alignmentX = Component.CENTER_ALIGNMENT

        val taglineLabel = JBLabel("No cloud. No server. AES-256-GCM encrypted.")
        taglineLabel.foreground = UIUtil.getLabelDisabledForeground()
        taglineLabel.font = taglineLabel.font.deriveFont(Font.ITALIC, 11f)
        taglineLabel.alignmentX = Component.CENTER_ALIGNMENT

        brandPanel.add(Box.createVerticalGlue())
        brandPanel.add(titleLabel)
        brandPanel.add(Box.createVerticalStrut(6))
        brandPanel.add(subtitleLabel)
        brandPanel.add(Box.createVerticalStrut(3))
        brandPanel.add(taglineLabel)
        brandPanel.add(Box.createVerticalStrut(32))

        val statusLabel = JBLabel("Not connected")
        statusLabel.icon = AllIcons.Nodes.EmptyNode
        statusLabel.foreground = UIUtil.getLabelDisabledForeground()
        statusLabel.font = statusLabel.font.deriveFont(13f)
        statusLabel.alignmentX = Component.CENTER_ALIGNMENT
        brandPanel.add(statusLabel)
        brandPanel.add(Box.createVerticalStrut(24))

        val buttonsPanel = JPanel(GridLayout(1, 2, 14, 0))
        buttonsPanel.isOpaque = false
        buttonsPanel.maximumSize = Dimension(380, 44)

        val createBtn = JButton("Create Workspace")
        createBtn.icon = AllIcons.General.Add
        createBtn.font = createBtn.font.deriveFont(Font.BOLD, 13f)
        createBtn.putClientProperty("JButton.buttonType", "default")
        createBtn.preferredSize = Dimension(180, 44)

        val joinBtn = JButton("Join Workspace")
        joinBtn.icon = AllIcons.Vcs.Fetch
        joinBtn.font = joinBtn.font.deriveFont(Font.BOLD, 13f)
        joinBtn.preferredSize = Dimension(180, 44)

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
        val headerPanel = JPanel(BorderLayout(10, 0))
        headerPanel.border = JBUI.Borders.empty(10, 14, 10, 14)

        val syncDot = PulseLabel()
        syncDot.icon = AllIcons.Actions.Refresh
        syncDot.text = "Syncing..."
        syncDot.font = syncDot.font.deriveFont(Font.BOLD, 13f)

        val peerChip = JBLabel("0 peers")
        peerChip.icon = AllIcons.Actions.GroupBy
        peerChip.font = peerChip.font.deriveFont(Font.BOLD, 14f)
        peerChip.foreground = Color(0x00, 0xBC, 0xD4)

        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        leftHeader.isOpaque = false
        leftHeader.add(syncDot)

        headerPanel.add(leftHeader, BorderLayout.WEST)
        headerPanel.add(peerChip, BorderLayout.EAST)

        // --- Secrets list ---
        val listModel = DefaultListModel<Pair<String, String>>()
        val revealedKeys = mutableSetOf<String>()
        var hoveredIndex = -1

        val secretList = JBList(listModel)
        secretList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        secretList.emptyText.text = "No secrets yet — click + to add one"

        secretList.cellRenderer = ListCellRenderer<Pair<String, String>> { _, pair, index, isSelected, _ ->
            val (k, v) = pair
            val accentColor = Color(0x00, 0xBC, 0xD4)
            val isHovered = index == hoveredIndex && !isSelected

            val card = JPanel(BorderLayout())
            card.border = JBUI.Borders.empty(4, 10, 4, 10)
            card.isOpaque = false

            val inner = object : JPanel(BorderLayout(14, 0)) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = background
                    g2.fillRoundRect(0, 0, width, height, 14, 14)
                    if (isSelected || isHovered) {
                        g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, if (isSelected) 255 else 30)
                        g2.fillRoundRect(0, 0, 5, height, 5, 5)
                    } else {
                        g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, 50)
                        g2.fillRoundRect(0, 0, 4, height, 4, 4)
                    }
                    if (isHovered && !isSelected) {
                        g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, 12)
                        g2.fillRoundRect(0, 0, width, height, 14, 14)
                    }
                    g2.dispose()
                }
            }
            inner.isOpaque = false
            inner.border = JBUI.Borders.empty(12, 16, 12, 16)
            inner.background = if (isSelected) {
                UIUtil.getListSelectionBackground(true)
            } else if (index % 2 == 0) {
                UIUtil.getListBackground()
            } else {
                UIUtil.getDecoratedRowColor()
            }

            val textPanel = JPanel()
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.isOpaque = false

            val keyLbl = JBLabel(k)
            keyLbl.font = keyLbl.font.deriveFont(Font.BOLD, 14f)
            keyLbl.foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getLabelForeground()
            textPanel.add(keyLbl)
            textPanel.add(Box.createVerticalStrut(4))

            val dots = minOf(v.length, 20).coerceAtLeast(8)
            val displayValue = if (revealedKeys.contains(k)) v else "•".repeat(dots)
            val valLbl = JBLabel(displayValue)
            valLbl.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            valLbl.foreground = if (isSelected) {
                val fg = UIUtil.getListSelectionForeground(true)
                Color(fg.red, fg.green, fg.blue, 180)
            } else {
                accentColor
            }
            textPanel.add(valLbl)

            inner.add(textPanel, BorderLayout.CENTER)

            val iconsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            iconsPanel.isOpaque = false

            val copyLabel = JBLabel(AllIcons.Actions.Copy)
            copyLabel.cursor = Cursor(Cursor.HAND_CURSOR)
            copyLabel.toolTipText = "Copy value"
            iconsPanel.add(copyLabel)

            val eyeLabel = JBLabel(
                if (revealedKeys.contains(k)) AllIcons.Actions.ToggleVisibility else AllIcons.Actions.Show
            )
            eyeLabel.cursor = Cursor(Cursor.HAND_CURSOR)
            eyeLabel.toolTipText = if (revealedKeys.contains(k)) "Hide" else "Reveal"
            iconsPanel.add(eyeLabel)

            inner.add(iconsPanel, BorderLayout.EAST)

            card.add(inner, BorderLayout.CENTER)
            card
        }

        secretList.fixedCellHeight = 72

        secretList.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = secretList.locationToIndex(e.point)
                if (idx != hoveredIndex) {
                    hoveredIndex = idx
                    secretList.repaint()
                }
            }
        })

        secretList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                hoveredIndex = -1
                secretList.repaint()
            }
            override fun mouseClicked(e: MouseEvent) {
                val idx = secretList.locationToIndex(e.point)
                if (idx < 0) return
                val cellBounds = secretList.getCellBounds(idx, idx) ?: return
                val relativeX = e.x - cellBounds.x
                val fromRight = cellBounds.width - relativeX
                if (fromRight in 0..30) {
                    val key = listModel.getElementAt(idx).first
                    if (revealedKeys.contains(key)) revealedKeys.remove(key) else revealedKeys.add(key)
                    secretList.repaint()
                } else if (fromRight in 31..60) {
                    val pair = listModel.getElementAt(idx)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(pair.second), null)
                    Notifications.Bus.notify(
                        Notification("Pync", "Copied ${pair.first}", NotificationType.INFORMATION), project
                    )
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

        actionGroup.add(object : AnAction("Delete Secret", "Delete the selected secret", AllIcons.Actions.GC) {
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

        actionGroup.add(object : AnAction("Copy Workspace Key", "Copy the workspace key to clipboard", AllIcons.General.CopyHovered) {
            override fun actionPerformed(e: AnActionEvent) {
                if (topicKey.isNotEmpty()) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(topicKey), null)
                    Notifications.Bus.notify(
                        Notification("Pync", "Workspace key copied", NotificationType.INFORMATION), project
                    )
                }
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = topicKey.isNotEmpty() }
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

        actionGroup.add(object : AnAction("Disconnect", "Disconnect but keep local data", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                sidecarService.destroy()
                secrets.clear()
                listModel.clear()
                revealedKeys.clear()
                role = ""
                topicKey = ""
                SwingUtilities.invokeLater {
                    statusLabel.text = "Disconnected"
                    statusLabel.icon = AllIcons.Nodes.EmptyNode
                    statusLabel.foreground = UIUtil.getLabelDisabledForeground()
                    rootPanel.showCard("connect")
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
        rootPanel.showCard("connect")

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

        // =====================================================================
        // SIDECAR LISTENER
        // =====================================================================
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
                        syncDot.startPulse()
                        rootPanel.showCard("secrets")
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
                        syncDot.stopPulse()
                        syncDot.icon = AllIcons.General.InspectionsOK
                        syncDot.text = "Synced"
                        syncDot.foreground = Color(0x4C, 0xAF, 0x50)
                        refreshList(parsed)
                        envWriter.autoSync(project, parsed)
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
