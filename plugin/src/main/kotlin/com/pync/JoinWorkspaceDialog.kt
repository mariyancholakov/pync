package com.pync

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class JoinWorkspaceDialog(project: Project?) : DialogWrapper(project) {

    private val topicKeyField = JBTextField()
    private val passphraseField = JBTextField()
    private var result: Pair<String, String>? = null

    init {
        title = "Join Workspace"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Topic key:") { cell(topicKeyField).focused().columns(30) }
            row("Passphrase:") { cell(passphraseField).columns(30) }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (topicKeyField.text.isBlank()) return ValidationInfo("Topic key required", topicKeyField)
        if (passphraseField.text.isBlank()) return ValidationInfo("Passphrase required", passphraseField)
        return null
    }

    override fun doOKAction() {
        result = Pair(topicKeyField.text.trim(), passphraseField.text)
        super.doOKAction()
    }

    fun getResult(): Pair<String, String>? = result
}
