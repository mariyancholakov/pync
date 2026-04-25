package com.pync

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CreateWorkspaceDialog(project: Project?) : DialogWrapper(project) {

    private val workspaceField = JBTextField()
    private val passphraseField = JBTextField()
    private var result: Pair<String, String>? = null

    init {
        title = "Create Workspace"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Workspace name:") { cell(workspaceField).focused().columns(30) }
            row("Passphrase:") { cell(passphraseField).columns(30) }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (workspaceField.text.isBlank()) return ValidationInfo("Workspace name required", workspaceField)
        if (passphraseField.text.isBlank()) return ValidationInfo("Passphrase required", passphraseField)
        return null
    }

    override fun doOKAction() {
        result = Pair(workspaceField.text.trim(), passphraseField.text)
        super.doOKAction()
    }

    fun getResult(): Pair<String, String>? = result
}
