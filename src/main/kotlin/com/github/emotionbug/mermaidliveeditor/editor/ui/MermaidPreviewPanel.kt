package com.github.emotionbug.mermaidliveeditor.editor.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.FileContentUtilCore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel

class MermaidPreviewPanel(
    private val project: Project,
    browser: JBCefBrowser
) : JPanel(BorderLayout()) {
    private val LOG = Logger.getInstance(MermaidPreviewPanel::class.java)

    val errorLabel = JLabel().apply {
        foreground = JBColor.RED
        isVisible = false
    }
    val previewPanel = JPanel(BorderLayout())
    private val mainPanel = JPanel(BorderLayout())

    var currentImageEditor: FileEditor? = null
        private set
    private var tempSvgFile: LightVirtualFile? = null
    private var lastSvg: String? = null

    init {
        val layeredPane = JLayeredPane()
        layeredPane.layout = null

        val browserComponent = browser.component
        browserComponent.bounds = Rectangle(0, 0, 0, 0)
        browserComponent.isVisible = false
        layeredPane.add(browserComponent, JLayeredPane.DEFAULT_LAYER)

        mainPanel.add(errorLabel, BorderLayout.NORTH)
        mainPanel.add(previewPanel, BorderLayout.CENTER)
        mainPanel.bounds = Rectangle(0, 0, 0, 0)
        layeredPane.add(mainPanel, JLayeredPane.PALETTE_LAYER)

        this.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                val r = Rectangle(0, 0, this@MermaidPreviewPanel.width, this@MermaidPreviewPanel.height)
                layeredPane.bounds = r
                mainPanel.bounds = r
                layeredPane.revalidate()
                layeredPane.repaint()
            }
        })

        this.add(layeredPane, BorderLayout.CENTER)
    }

    fun updateImageEditor(svg: String, contextMenuListener: MouseAdapter) {
        if (svg == lastSvg && currentImageEditor != null) return
        if (svg.isBlank()) return

        try {
            val svgBytes = svg.toByteArray(Charsets.UTF_8)
            if (tempSvgFile == null) {
                tempSvgFile =
                    LightVirtualFile("preview.svg", FileTypeManager.getInstance().getFileTypeByExtension("svg"), svg)
            } else {
                ApplicationManager.getApplication().runWriteAction {
                    try {
                        tempSvgFile!!.setBinaryContent(svgBytes)
                    } catch (e: Exception) {
                        LOG.error("Failed to update temp SVG file content", e)
                    }
                }
            }

            val providers = FileEditorProviderManager.getInstance().getProviderList(project, tempSvgFile!!)
            val imageProvider = providers.firstOrNull { it.editorTypeId == "images" || it.editorTypeId == "svg-editor" }
                ?: providers.firstOrNull { it !is TextEditorProvider }
                ?: providers.firstOrNull()

            if (imageProvider != null) {
                if (currentImageEditor != null && currentImageEditor!!.javaClass == imageProvider.javaClass) {
                    FileContentUtilCore.reparseFiles(tempSvgFile!!)
                    if (currentImageEditor !is TextEditor && currentImageEditor !is TextEditorWithPreview) {
                        val state = currentImageEditor!!.getState(FileEditorStateLevel.FULL)
                        currentImageEditor!!.setState(state)
                    }
                } else {
                    val oldEditor = currentImageEditor
                    val oldState = oldEditor?.getState(FileEditorStateLevel.FULL)

                    val newEditor = imageProvider.createEditor(project, tempSvgFile!!)
                    if (newEditor is TextEditorWithPreview) {
                        newEditor.setLayout(TextEditorWithPreview.Layout.SHOW_PREVIEW)
                    }

                    previewPanel.removeAll()
                    previewPanel.add(newEditor.component, BorderLayout.CENTER)
                    addContextMenuRecursively(newEditor.component, contextMenuListener)
                    currentImageEditor = newEditor

                    oldState?.let {
                        try {
                            newEditor.setState(it)
                        } catch (e: Exception) {
                            LOG.warn("Failed to restore editor state", e)
                        }
                    }
                    oldEditor?.let { Disposer.dispose(it) }
                }
            }
            previewPanel.revalidate()
            previewPanel.repaint()
            lastSvg = svg
        } catch (e: Exception) {
            LOG.error("Failed to update image editor", e)
        }
    }

    private fun addContextMenuRecursively(component: Component, listener: MouseAdapter) {
        component.addMouseListener(listener)
        if (component is Container) {
            for (child in component.components) {
                addContextMenuRecursively(child, listener)
            }
        }
    }

    fun dispose() {
        currentImageEditor?.let { Disposer.dispose(it) }
    }
}
