package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class MermaidFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.fileType is MermaidFileType
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val editor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val preview = MermaidPreviewEditor(project, file)
        return TextEditorWithPreview(editor, preview)
    }

    override fun getEditorTypeId(): String = "mermaid-split-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
