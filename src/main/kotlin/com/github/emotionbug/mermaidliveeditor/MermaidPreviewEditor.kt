package com.github.emotionbug.mermaidliveeditor

import com.github.emotionbug.mermaidliveeditor.editor.actions.SavePptxAction
import com.github.emotionbug.mermaidliveeditor.editor.actions.SaveSvgAction
import com.github.emotionbug.mermaidliveeditor.editor.browser.MermaidBrowserManager
import com.github.emotionbug.mermaidliveeditor.editor.ui.MermaidPreviewPanel
import com.google.gson.Gson
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class MermaidPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(),
    FileEditor {
    private val LOG = Logger.getInstance(MermaidPreviewEditor::class.java)

    private val browserManager = MermaidBrowserManager(this)
    private val ui = MermaidPreviewPanel(project, browserManager.browser)

    private var documentListener: DocumentListener? = null
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var isSkeletonLoaded = false
    private var lastMermaidJsUrl: String? = null
    private val gson = Gson()
    private var lastSvg: String? = null

    private val contextMenuListener = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = showPopupMenu(e)
        override fun mouseReleased(e: MouseEvent) = showPopupMenu(e)

        private fun showPopupMenu(e: MouseEvent) {
            if (e.isPopupTrigger) {
                val group = DefaultActionGroup()
                group.add(SaveSvgAction(project) { lastSvg })
                group.add(SavePptxAction(project) { lastSvg })

                val popupMenu = ActionManager.getInstance().createActionPopupMenu("MermaidPreviewPopupMenu", group)
                popupMenu.component.show(e.component, e.x, e.y)
            }
        }
    }

    init {
        browserManager.jsQuery.addHandler { svg ->
            ApplicationManager.getApplication().invokeLater {
                lastSvg = svg
                ui.errorLabel.isVisible = false
                ui.updateImageEditor(svg, contextMenuListener)

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
                isSkeletonLoaded = true
                ApplicationManager.getApplication().invokeLater {
                    runReadAction { FileDocumentManager.getInstance().getDocument(file)?.text }?.let { text ->
                        updateAlarm.cancelAllRequests()
                        updateAlarm.addRequest({ updatePreview(text) }, 0)
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
    }

    private fun updatePreview(text: String) {
        val currentUrl = MermaidSettingsState.instance.mermaidJsUrl
        if (!isSkeletonLoaded || currentUrl != lastMermaidJsUrl) {
            loadSkeleton(text)
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
        val js = """
            (async () => {
                const text = '$escapedJsText';
                try {
                    if (typeof mermaid === 'undefined') {
                        throw new Error('Mermaid library not loaded yet');
                    }
                    try {
                        await mermaid.parse(text);
                    } catch (err) {
                        console.error(err);
                        const errorList = Array.isArray(err) ? err : [err];
                        const errorData = {
                            errors: errorList.map(e => ({
                                message: e.message || e.toString(),
                                line: e.hash ? e.hash.line : (e.loc ? e.loc.first_line : (e.line !== undefined ? e.line : -1)),
                                column: e.hash ? (e.hash.loc ? e.hash.loc.first_column : -1) : (e.loc ? e.loc.first_column : (e.column !== undefined ? e.column : -1))
                            }))
                        };
                        ${browserManager.errorJsQuery.inject("JSON.stringify(errorData)")};
                        return;
                    }
                    const id = 'mermaid-svg-' + Date.now();
                    const container = document.getElementById('mermaid-container');
                    const { svg } = await mermaid.render(id, text, container);
                    ${browserManager.jsQuery.inject("svg")};
                } catch (err) {
                    console.error(err);
                    const errorData = {
                        errors: [{
                            message: err.message || err.toString(),
                            line: err.hash ? err.hash.line : (err.loc ? err.loc.first_line : (err.line !== undefined ? err.line : -1)),
                            column: err.hash ? (err.hash.loc ? err.hash.loc.first_column : -1) : (err.loc ? err.loc.first_column : (err.column !== undefined ? err.column : -1))
                        }]
                    };
                    ${browserManager.errorJsQuery.inject("JSON.stringify(errorData)")};
                }
            })();
        """.trimIndent()
        browserManager.browser.cefBrowser.executeJavaScript(js, browserManager.browser.cefBrowser.url, 0)
    }

    private fun loadSkeleton(initialText: String) {
        lastMermaidJsUrl = MermaidSettingsState.instance.mermaidJsUrl
        val escapedInitialText = StringUtil.escapeStringCharacters(initialText)

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    html, body, #mermaid-container {
                        width: 100%;
                        height: 100%;
                        margin: 0;
                        padding: 0;
                        overflow: hidden;
                    }
                </style>
                <script>
                    function reportError(msg) {
                        const errorData = {
                            errors: [{ message: msg, line: -1, column: -1 }]
                        };
                        ${browserManager.errorJsQuery.inject("JSON.stringify(errorData)")};
                    }
                </script>
                <script src="${lastMermaidJsUrl}" onerror="reportError('Failed to load Mermaid.js from ' + this.src)"></script>
                <script>
                    document.addEventListener("DOMContentLoaded", function() {
                        try {
                            mermaid.initialize({
                                startOnLoad: false,
                                theme: (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'default',
                                securityLevel: 'loose',
                                flowchart: { useMaxWidth: false },
                                sequence: { useMaxWidth: false },
                                gantt: { useMaxWidth: false },
                                journey: { useMaxWidth: false },
                                class: { useMaxWidth: false },
                                state: { useMaxWidth: false },
                                er: { useMaxWidth: false },
                                pie: { useMaxWidth: false }
                            });
                            
                            const initialText = '$escapedInitialText';
                            if (initialText) {
                                (async () => {
                                    try {
                                        try {
                                            await mermaid.parse(initialText);
                                        } catch (err) {
                                            console.error(err);
                                            const errorList = Array.isArray(err) ? err : [err];
                                            const errorData = {
                                                errors: errorList.map(e => ({
                                                    message: e.message || e.toString(),
                                                    line: e.hash ? e.hash.line : (e.loc ? e.loc.first_line : (e.line !== undefined ? e.line : -1)),
                                                    column: e.hash ? (e.hash.loc ? e.hash.loc.first_column : -1) : (e.loc ? e.loc.first_column : (e.column !== undefined ? e.column : -1))
                                                }))
                                            };
                                            ${browserManager.errorJsQuery.inject("JSON.stringify(errorData)")};
                                            return;
                                        }
                                        
                                        const id = 'mermaid-svg-' + Date.now();
                                        const container = document.getElementById('mermaid-container');
                                        const { svg } = await mermaid.render(id, initialText, container);
                                        ${browserManager.jsQuery.inject("svg")};
                                    } catch (err) {
                                        console.error(err);
                                        const errorData = {
                                            errors: [{
                                                message: err.message || err.toString(),
                                                line: err.hash ? err.hash.line : (err.loc ? err.loc.first_line : (err.line !== undefined ? err.line : -1)),
                                                column: err.hash ? (err.hash.loc ? err.hash.loc.first_column : -1) : (err.loc ? err.loc.first_column : (err.column !== undefined ? err.column : -1))
                                            }]
                                        };
                                        ${browserManager.errorJsQuery.inject("JSON.stringify(errorData)")};
                                    }
                                })();
                            }
                        } catch (e) {
                            console.error(e);
                            const errorData = {
                                errors: [{
                                    message: e.message || e.toString(),
                                    line: -1,
                                    column: -1
                                }]
                            };
                            ${browserManager.errorJsQuery.inject("JSON.stringify(errorData)")};
                        }
                    });
                </script>
            </head>
            <body>
                <div id="mermaid-container" style="width: 2000px; height: 2000px;"></div>
            </body>
            </html>
        """.trimIndent()
        isSkeletonLoaded = false
        browserManager.browser.loadHTML(html)
    }

    override fun getFile(): VirtualFile = file
    override fun getComponent(): JComponent = ui
    override fun getPreferredFocusedComponent(): JComponent = ui
    override fun getName(): String = "Mermaid Preview"
    override fun setState(state: FileEditorState) {
        ui.currentImageEditor?.setState(state)
    }

    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        ui.currentImageEditor?.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        ui.currentImageEditor?.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = ui.currentImageEditor?.currentLocation

    override fun dispose() {
        runReadAction { FileDocumentManager.getInstance().getDocument(file) }?.let {
            documentListener?.let { listener -> it.removeDocumentListener(listener) }
        }
        ui.dispose()
    }
}
