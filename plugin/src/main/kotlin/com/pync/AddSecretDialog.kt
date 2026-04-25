package com.pync

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class AddSecretDialog(project: Project) : DialogWrapper(project) {

    private val keyField = JBTextField()
    private val valueField = JBTextField()
    private var result: Pair<String, String>? = null

    init {
        title = "Add Secret"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Key:") { cell(keyField).focused().columns(30) }
            row("Value:") { cell(valueField).columns(30) }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (keyField.text.isBlank()) {
            return ValidationInfo("Key must not be empty", keyField)
        }
        return null
    }

    override fun doOKAction() {
        result = Pair(keyField.text.trim(), valueField.text)
        super.doOKAction()
    }

    fun getResult(): Pair<String, String>? = result
}
