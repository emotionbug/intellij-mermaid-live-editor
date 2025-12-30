package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class MermaidSettingsConfigurable : Configurable {
    private var jsSourceCombo: ComboBox<MermaidJsSource>? = null
    private var mermaidJsUrlField: JBTextField? = null
    private var mermaidJsFileField: TextFieldWithBrowseButton? = null

    private lateinit var urlRow: Row
    private lateinit var fileRow: Row

    override fun getDisplayName(): String = "Mermaid Live Editor"

    override fun createComponent(): JComponent {
        val settings = MermaidSettingsState.instance
        return panel {
            row("Mermaid.js Source:") {
                jsSourceCombo = comboBox(DefaultComboBoxModel(MermaidJsSource.entries.toTypedArray()))
                    .applyToComponent {
                        selectedItem = settings.jsSource
                        addActionListener {
                            updateVisibleRows()
                        }
                    }
                    .component
            }

            urlRow = row("Mermaid.js CDN URL:") {
                mermaidJsUrlField = textField()
                    .applyToComponent {
                        text = settings.mermaidJsUrl
                    }
                    .comment("Enter the CDN URL for mermaid.min.js (e.g., https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js)")
                    .component
            }

            fileRow = row("Mermaid.js Local File:") {
                mermaidJsFileField = textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("js").withTitle("Select Mermaid.js File")
                )
                    .applyToComponent {
                        text = settings.mermaidJsUrl
                    }
                    .comment("Select a local mermaid.min.js file")
                    .component
            }

            updateVisibleRows()
        }
    }

    private fun updateVisibleRows() {
        val selected = jsSourceCombo?.selectedItem as? MermaidJsSource
        urlRow.visible(selected == MermaidJsSource.CDN)
        fileRow.visible(selected == MermaidJsSource.LOCAL_FILE)
    }

    override fun isModified(): Boolean {
        val settings = MermaidSettingsState.instance
        val selectedSource = jsSourceCombo?.selectedItem as? MermaidJsSource
        if (selectedSource != settings.jsSource) return true
        
        val currentUrl = if (selectedSource == MermaidJsSource.CDN) mermaidJsUrlField?.text else mermaidJsFileField?.text
        return currentUrl != settings.mermaidJsUrl
    }

    override fun apply() {
        val settings = MermaidSettingsState.instance
        val selectedSource = jsSourceCombo?.selectedItem as? MermaidJsSource ?: MermaidJsSource.BUILT_IN
        settings.jsSource = selectedSource
        settings.mermaidJsUrl = when (selectedSource) {
            MermaidJsSource.CDN -> mermaidJsUrlField?.text ?: ""
            MermaidJsSource.LOCAL_FILE -> mermaidJsFileField?.text ?: ""
            else -> ""
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.syncPublisher(MermaidSettingsState.TOPIC).settingsChanged()
    }

    override fun reset() {
        val settings = MermaidSettingsState.instance
        jsSourceCombo?.selectedItem = settings.jsSource
        mermaidJsUrlField?.text = settings.mermaidJsUrl
        mermaidJsFileField?.text = settings.mermaidJsUrl
        updateVisibleRows()
    }

    override fun disposeUIResources() {
        jsSourceCombo = null
        mermaidJsUrlField = null
        mermaidJsFileField = null
    }
}
