package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

class MermaidPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val component = JPanel(BorderLayout())
    private var editor: Editor? = null

    init {
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null) {
            editor = EditorFactory.getInstance().createViewer(document, project)
            component.add(editor!!.component, BorderLayout.CENTER)
        }
    }

    override fun getFile(): VirtualFile = file

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent? = editor?.contentComponent

    override fun getName(): String = "Mermaid Preview"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        editor?.let {
            EditorFactory.getInstance().releaseEditor(it)
        }
    }
}
