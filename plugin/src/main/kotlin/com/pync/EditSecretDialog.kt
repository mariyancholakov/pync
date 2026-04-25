package com.pync

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class EditSecretDialog(
    project: Project,
    existingKey: String,
    existingValue: String
) : DialogWrapper(project) {

    private val keyField = JBTextField(existingKey).apply { isEditable = false }
    private val valueField = JBTextField(existingValue)
    private var result: String? = null

    init {
        title = "Edit Secret"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Key:") { cell(keyField).columns(30) }
            row("Value:") { cell(valueField).focused().columns(30) }
        }
    }

    override fun doOKAction() {
        result = valueField.text
        super.doOKAction()
    }

    fun getResult(): String? = result
}
