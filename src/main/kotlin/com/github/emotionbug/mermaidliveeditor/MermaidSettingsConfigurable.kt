package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextField

class MermaidSettingsConfigurable : Configurable {
    private var mermaidJsUrlField: JTextField? = null

    override fun getDisplayName(): String = "Mermaid Live Editor"

    override fun createComponent(): JComponent {
        val settings = MermaidSettingsState.instance
        return panel {
            row("Mermaid.js URL:") {
                mermaidJsUrlField = textField()
                    .applyToComponent {
                        text = settings.mermaidJsUrl
                    }
                    .comment("Enter the URL for the mermaid.js file (e.g., from a CDN)")
                    .component
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = MermaidSettingsState.instance
        return mermaidJsUrlField?.text != settings.mermaidJsUrl
    }

    override fun apply() {
        val settings = MermaidSettingsState.instance
        settings.mermaidJsUrl = mermaidJsUrlField?.text ?: ""
    }

    override fun reset() {
        val settings = MermaidSettingsState.instance
        mermaidJsUrlField?.text = settings.mermaidJsUrl
    }

    override fun disposeUIResources() {
        mermaidJsUrlField = null
    }
}
