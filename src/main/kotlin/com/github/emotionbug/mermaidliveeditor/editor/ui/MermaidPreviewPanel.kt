package com.github.emotionbug.mermaidliveeditor.editor.ui

import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

class MermaidPreviewPanel(
    browser: JBCefBrowser
) : JPanel(BorderLayout()) {
    val errorLabel = JLabel().apply {
        foreground = JBColor.RED
        isVisible = false
    }

    init {
        background = JBColor.WHITE
        add(errorLabel, BorderLayout.NORTH)
        add(browser.component, BorderLayout.CENTER)
    }

    fun dispose() {
        // do nothing.
    }
}
