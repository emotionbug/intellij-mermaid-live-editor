package com.github.emotionbug.mermaidliveeditor

import com.github.emotionbug.mermaidliveeditor.editor.browser.MermaidBrowserManager
import com.github.emotionbug.mermaidliveeditor.editor.browser.MermaidResourceHandler
import com.github.emotionbug.mermaidliveeditor.editor.ui.MermaidPreviewPanel
import com.google.gson.Gson
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class MermaidPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(),
    FileEditor {
    private val LOG = Logger.getInstance(MermaidPreviewEditor::class.java)

    private val browserManager = MermaidBrowserManager(project, this) { lastSvg }
    private val ui = MermaidPreviewPanel(browserManager.browser)

    private var documentListener: DocumentListener? = null
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var isSkeletonLoaded = false
    private var lastMermaidJsUrl: String? = null
    private val gson = Gson()
    private var lastSvg: String? = null

    fun getJsUrl(): String {
        return when (MermaidSettingsState.instance.jsSource) {
            MermaidJsSource.BUILT_IN -> "${MermaidResourceHandler.RESOURCE_HANDLER_URL}${MermaidResourceHandler.DEFAULT_MERMAID_JS}"
            MermaidJsSource.CDN -> MermaidSettingsState.instance.mermaidJsUrl
            MermaidJsSource.LOCAL_FILE -> {
                val path = MermaidSettingsState.instance.mermaidJsUrl.removePrefix("file://").removePrefix("file:/")
                val file = java.io.File(path)
                if (file.exists()) {
                    "${MermaidResourceHandler.RESOURCE_HANDLER_URL}${MermaidResourceHandler.LOCAL_FILE_MERMAID_JS}"
                } else {
                    "${MermaidResourceHandler.RESOURCE_HANDLER_URL}${MermaidResourceHandler.DEFAULT_MERMAID_JS}"
                }
            }
        }.let {
            it.ifBlank { "${MermaidResourceHandler.RESOURCE_HANDLER_URL}${MermaidResourceHandler.DEFAULT_MERMAID_JS}" }
        }.let {
            // Add timestamp to prevent caching of the JS file itself
            if (it.contains("?")) "$it&t=${System.currentTimeMillis()}" else "$it?t=${System.currentTimeMillis()}"
        }
    }

    init {
        browserManager.jsQuery.addHandler { svg ->
            ApplicationManager.getApplication().invokeLater {
                lastSvg = svg
                ui.errorLabel.isVisible = false

                file.putUserData(MERMAID_ERROR_KEY, null)
                runReadAction { PsiManager.getInstance(project).findFile(file) }?.let {
                    DaemonCodeAnalyzer.getInstance(project).restart(it)
                }
            }
            null
        }

        browserManager.errorJsQuery.addHandler { errorJson ->
            ApplicationManager.getApplication().invokeLater {
                val errorData = try {
                    gson.fromJson(errorJson, MermaidErrorData::class.java)
                } catch (e: Exception) {
                    try {
                        val singleError = gson.fromJson(errorJson, MermaidError::class.java)
                        MermaidErrorData(listOf(singleError))
                    } catch (e2: Exception) {
                        MermaidErrorData(listOf(MermaidError(errorJson)))
                    }
                }

                if (errorData.errors.isNotEmpty()) {
                    val firstError = errorData.errors[0]
                    val extraCount = errorData.errors.size - 1
                    val extraText = if (extraCount > 0) "<br/>(and $extraCount more errors)" else ""
                    val escapedMessage = StringUtil.escapeXmlEntities(firstError.message)
                    ui.errorLabel.text =
                        "<html>Mermaid Error:<br/>${escapedMessage.replace("\n", "<br/>")}$extraText</html>"
                    ui.errorLabel.isVisible = true
                    LOG.warn("[Mermaid Error] $errorJson")
                } else {
                    ui.errorLabel.isVisible = false
                }
                ui.revalidate()
                ui.repaint()

                file.putUserData(MERMAID_ERROR_KEY, errorData)
                runReadAction { PsiManager.getInstance(project).findFile(file) }?.let {
                    DaemonCodeAnalyzer.getInstance(project).restart(it)
                }
            }
            null
        }

        browserManager.browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                LOG.info("Skeleton loaded with status: $httpStatusCode")

                val mermaidJsUrl = getJsUrl()
                val onMermaidError = browserManager.errorJsQuery.inject("JSON.stringify(errorData)")
                val onMermaidRendered = browserManager.jsQuery.inject("svg")

                ApplicationManager.getApplication().executeOnPooledThread {
                    val initialText = runReadAction { FileDocumentManager.getInstance().getDocument(file)?.text } ?: ""
                    val escapedInitialText = StringUtil.escapeStringCharacters(initialText)

                    val initJs = """
                        if (window.initialize) {
                            window.initialize({
                                mermaidJsUrl: '$mermaidJsUrl',
                                onMermaidError: function(errorData) { $onMermaidError },
                                onMermaidRendered: function(svg) { $onMermaidRendered },
                                initialText: '$escapedInitialText'
                            });
                        }
                    """.trimIndent()

                    ApplicationManager.getApplication().invokeLater {
                        browser?.executeJavaScript(initJs, browser.url, 0)
                        isSkeletonLoaded = true
                    }
                }
            }
        }, browserManager.browser.cefBrowser)

        runReadAction { FileDocumentManager.getInstance().getDocument(file) }?.let { document ->
            updatePreview(document.text)
            documentListener = object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    updateAlarm.cancelAllRequests()
                    updateAlarm.addRequest({ updatePreview(event.document.text) }, 300)
                }
            }
            document.addDocumentListener(documentListener!!)
        }

        project.messageBus.connect(this).subscribe(MermaidSettingsState.TOPIC, MermaidSettingsListener {
            ApplicationManager.getApplication().invokeLater {
                loadSkeleton()
            }
        })
    }

    private fun updatePreview(text: String) {
        val settings = MermaidSettingsState.instance
        val currentUrl = when (settings.jsSource) {
            MermaidJsSource.BUILT_IN -> "BUILT_IN"
            else -> settings.mermaidJsUrl
        }
        if (!isSkeletonLoaded || currentUrl != lastMermaidJsUrl) {
            loadSkeleton()
            return
        }

        if (text.isBlank()) {
            ApplicationManager.getApplication().invokeLater {
                ui.errorLabel.isVisible = false
                file.putUserData(MERMAID_ERROR_KEY, null)
                runReadAction { PsiManager.getInstance(project).findFile(file) }?.let {
                    DaemonCodeAnalyzer.getInstance(project).restart(it)
                }
            }
            return
        }

        val escapedJsText = StringUtil.escapeStringCharacters(text)
        val js = "if (window.updateDiagram) window.updateDiagram('$escapedJsText');"
        browserManager.browser.cefBrowser.executeJavaScript(js, browserManager.browser.cefBrowser.url, 0)
    }

    private fun loadSkeleton() {
        LOG.info("Loading skeleton... current URL: ${browserManager.browser.cefBrowser.url}")
        val settings = MermaidSettingsState.instance
        lastMermaidJsUrl = when (settings.jsSource) {
            MermaidJsSource.BUILT_IN -> "BUILT_IN"
            else -> settings.mermaidJsUrl
        }
        isSkeletonLoaded = false
        // Use a timestamp to force reload even if the URL is the same
        val url = "https://mermaid-preview/?t=${System.currentTimeMillis()}"
        browserManager.browser.loadURL(url)
    }

    override fun getFile(): VirtualFile = file
    override fun getComponent(): JComponent = ui
    override fun getPreferredFocusedComponent(): JComponent = ui
    override fun getName(): String = "Mermaid Preview"
    override fun setState(state: FileEditorState) {
        // Nothing to do
    }

    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        // Nothing to do
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        // Nothing to do
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        runReadAction { FileDocumentManager.getInstance().getDocument(file) }?.let {
            documentListener?.let { listener -> it.removeDocumentListener(listener) }
        }
        ui.dispose()
    }
}
