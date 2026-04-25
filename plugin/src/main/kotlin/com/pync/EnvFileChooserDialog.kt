package com.pync

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class EnvFileChooserDialog(
    project: Project,
    private val labels: List<String>
) : DialogWrapper(project) {

    private val listModel = DefaultListModel<String>().apply { labels.forEach { addElement(it) } }
    private val fileList = JBList(listModel)
    private var selectedIndex: Int = -1

    init {
        title = "Select .env File"
        setOKButtonText("Sync Here")
        init()
        fileList.selectedIndex = 0
        isOKActionEnabled = labels.isNotEmpty()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.border = JBUI.Borders.empty(8, 4, 4, 4)

        val description = JBLabel("Multiple .env files found. Choose where to sync secrets:")
        description.foreground = UIUtil.getLabelDisabledForeground()
        description.font = description.font.deriveFont(12f)
        panel.add(description, BorderLayout.NORTH)

        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.fixedCellHeight = 44
        fileList.cellRenderer = ListCellRenderer<String> { _, value, index, isSelected, _ ->
            val cell = JPanel(BorderLayout(10, 0))
            cell.border = JBUI.Borders.empty(8, 12, 8, 12)

            if (isSelected) {
                cell.background = UIUtil.getListSelectionBackground(true)
            } else {
                cell.background = if (index % 2 == 0) UIUtil.getListBackground() else UIUtil.getDecoratedRowColor()
            }

            val icon = JBLabel(AllIcons.FileTypes.Properties)
            cell.add(icon, BorderLayout.WEST)

            val textPanel = JPanel()
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.isOpaque = false

            val nameLabel = JBLabel(value.substringAfterLast("/").substringAfterLast("\\"))
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 13f)
            if (isSelected) nameLabel.foreground = UIUtil.getListSelectionForeground(true)
            textPanel.add(nameLabel)

            val pathLabel = JBLabel(value)
            pathLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            pathLabel.foreground = if (isSelected) {
                val fg = UIUtil.getListSelectionForeground(true)
                Color(fg.red, fg.green, fg.blue, 180)
            } else {
                UIUtil.getLabelDisabledForeground()
            }
            textPanel.add(pathLabel)

            cell.add(textPanel, BorderLayout.CENTER)

            val checkIcon = JBLabel(
                if (isSelected) AllIcons.Actions.Checked else AllIcons.Actions.Checked_selected
            )
            checkIcon.isVisible = isSelected
            cell.add(checkIcon, BorderLayout.EAST)

            cell
        }

        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) doOKAction()
            }
        })

        val scrollPane = JBScrollPane(fileList)
        scrollPane.preferredSize = Dimension(380, minOf(labels.size * 44 + 4, 220))
        @Suppress("DEPRECATION")
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getSeparatorColor())
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        selectedIndex = fileList.selectedIndex
        super.doOKAction()
    }

    fun getSelectedIndex(): Int = selectedIndex
}
