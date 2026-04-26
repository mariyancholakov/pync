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

class AddSecretDialog(project: Project) : DialogWrapper(project) {

    private val keyField = JBTextField().apply { font = font.deriveFont(20f) }
    private val valueField = JBTextField().apply { font = font.deriveFont(20f) }
    private var result: Pair<String, String>? = null

    init {
        title = "Add Secret"
        init()
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateValidation()
            override fun removeUpdate(e: DocumentEvent) = updateValidation()
            override fun changedUpdate(e: DocumentEvent) = updateValidation()
        }
        keyField.document.addDocumentListener(listener)
        valueField.document.addDocumentListener(listener)
        updateValidation()
    }

    private fun updateValidation() {
        val keyEmpty = keyField.text.isNullOrBlank()
        val valueEmpty = valueField.text.isNullOrBlank()
        when {
            keyEmpty -> setErrorText("Key must not be empty")
            valueEmpty -> setErrorText("Value must not be empty")
            else -> setErrorText(null)
        }
        isOKActionEnabled = !keyEmpty && !valueEmpty
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Key:") { cell(keyField).focused().columns(40) }
            row("Value:") { cell(valueField).columns(40) }
        }.apply {
            font = font.deriveFont(20f)
        }
    }

    override fun doOKAction() {
        result = Pair(keyField.text.trim(), valueField.text)
        super.doOKAction()
    }

    fun getResult(): Pair<String, String>? = result
}
