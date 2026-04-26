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

class JoinWorkspaceDialog(project: Project?) : DialogWrapper(project) {

    private val topicKeyField = JBTextField().apply { font = font.deriveFont(20f) }
    private val passphraseField = JBTextField().apply { font = font.deriveFont(20f) }
    private var result: Pair<String, String>? = null

    init {
        title = "Join Workspace"
        init()
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateValidation()
            override fun removeUpdate(e: DocumentEvent) = updateValidation()
            override fun changedUpdate(e: DocumentEvent) = updateValidation()
        }
        topicKeyField.document.addDocumentListener(listener)
        passphraseField.document.addDocumentListener(listener)
        updateValidation()
    }

    private fun updateValidation() {
        val keyEmpty = topicKeyField.text.isNullOrBlank()
        val passEmpty = passphraseField.text.isNullOrBlank()
        when {
            keyEmpty -> setErrorText("Topic key required")
            passEmpty -> setErrorText("Passphrase required")
            else -> setErrorText(null)
        }
        isOKActionEnabled = !keyEmpty && !passEmpty
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Topic key:") { cell(topicKeyField).focused().columns(40) }
            row("Passphrase:") { cell(passphraseField).columns(40) }
        }.apply {
            font = font.deriveFont(20f)
        }
    }

    override fun doOKAction() {
        result = Pair(topicKeyField.text.trim(), passphraseField.text)
        super.doOKAction()
    }

    fun getResult(): Pair<String, String>? = result
}
