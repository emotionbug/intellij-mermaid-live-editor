package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.icons.AllIcons
import javax.swing.Icon

object MermaidFileType : LanguageFileType(MermaidLanguage) {
    override fun getName(): String = "Mermaid File"
    override fun getDescription(): String = "Mermaid diagram file"
    override fun getDefaultExtension(): String = "mermaid"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}
