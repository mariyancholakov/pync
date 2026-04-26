package com.pync

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.awt.Font
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CreateWorkspaceDialog(project: Project?) : DialogWrapper(project) {

    private val workspaceField = JBTextField().apply { font = font.deriveFont(20f) }
    private val passphraseField = JBTextField().apply { font = font.deriveFont(20f) }
    private var result: Pair<String, String>? = null

    init {
        title = "Create Workspace"
        init()
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateValidation()
            override fun removeUpdate(e: DocumentEvent) = updateValidation()
            override fun changedUpdate(e: DocumentEvent) = updateValidation()
        }
        workspaceField.document.addDocumentListener(listener)
        passphraseField.document.addDocumentListener(listener)
        updateValidation()
    }

    private fun updateValidation() {
        val nameEmpty = workspaceField.text.isNullOrBlank()
        val passEmpty = passphraseField.text.isNullOrBlank()
        when {
            nameEmpty -> setErrorText("Workspace name required")
            passEmpty -> setErrorText("Passphrase required")
            else -> setErrorText(null)
        }
        isOKActionEnabled = !nameEmpty && !passEmpty
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Workspace name:") { cell(workspaceField).focused().columns(40) }
            row("Passphrase:") { cell(passphraseField).columns(40) }
        }.apply {
            font = font.deriveFont(20f)
        }
    }

    override fun doOKAction() {
        result = Pair(workspaceField.text.trim(), passphraseField.text)
        super.doOKAction()
    }

    fun getResult(): Pair<String, String>? = result
}
