package com.github.emotionbug.mermaidliveeditor

import com.google.gson.Gson
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.FileContentUtilCore
import com.intellij.util.LocalTimeCounter
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class MermaidPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(),
    FileEditor {
    private val LOG = Logger.getInstance(MermaidPreviewEditor::class.java)
    private val component = JPanel(BorderLayout())
    private val previewPanel = JPanel(BorderLayout())
    private val errorLabel = JLabel().apply {
        foreground = JBColor.RED
        isVisible = false
    }
    private val browser = JBCefBrowser()
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val errorJsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var documentListener: DocumentListener? = null
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var isSkeletonLoaded = false
    private var lastMermaidJsUrl: String? = null
    private val gson = Gson()

    private var currentImageEditor: FileEditor? = null
    private var tempSvgFile: LightVirtualFile? = null

    init {
        component.add(errorLabel, BorderLayout.NORTH)
        component.add(previewPanel, BorderLayout.CENTER)

        // Browser must be in the component tree for JCEF to work correctly in some cases
        val browserComponent = browser.component
        browserComponent.preferredSize = java.awt.Dimension(0, 0)
        component.add(browserComponent, BorderLayout.SOUTH)

        jsQuery.addHandler { svg ->
            ApplicationManager.getApplication().invokeLater {
                errorLabel.isVisible = false
                updateImageEditor(svg)

                // Clear error info
                file.putUserData(MERMAID_ERROR_KEY, null)
                val psiFile = runReadAction { PsiManager.getInstance(project).findFile(file) }
                if (psiFile != null) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                }
            }
            null
        }

        errorJsQuery.addHandler { errorJson ->
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
                    errorLabel.text =
                        "<html>Mermaid Error:<br/>${escapedMessage.replace("\n", "<br/>")}$extraText</html>"
                    errorLabel.isVisible = true
                    LOG.warn("[Mermaid Error] $errorJson")
                } else {
                    errorLabel.isVisible = false
                }
                component.revalidate()
                component.repaint()

                // Save error info and trigger re-annotation
                file.putUserData(MERMAID_ERROR_KEY, errorData)
                val psiFile = runReadAction { PsiManager.getInstance(project).findFile(file) }
                if (psiFile != null) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                }
            }
            null
        }

        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int
            ): Boolean {
                val logMsg = "[Mermaid Console] [$level] $source:$line: $message"
                when (level) {
                    CefSettings.LogSeverity.LOGSEVERITY_ERROR, CefSettings.LogSeverity.LOGSEVERITY_FATAL -> LOG.error(
                        logMsg
                    )

                    CefSettings.LogSeverity.LOGSEVERITY_WARNING -> LOG.warn(logMsg)
                    else -> LOG.info(logMsg)
                }
                return false
            }
        }, browser.cefBrowser)

        browser.jbCefClient.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(
                browser: CefBrowser?,
                frame: CefFrame?,
                params: CefContextMenuParams?,
                model: CefMenuModel?
            ) {
                model?.addItem(CefMenuModel.MenuId.MENU_ID_USER_FIRST, "Open DevTools")
            }

            override fun onContextMenuCommand(
                browser: CefBrowser?,
                frame: CefFrame?,
                params: CefContextMenuParams?,
                commandId: Int,
                eventFlags: Int
            ): Boolean {
                if (commandId == CefMenuModel.MenuId.MENU_ID_USER_FIRST) {
                    this@MermaidPreviewEditor.browser.openDevtools()
                    return true
                }
                return false
            }
        }, browser.cefBrowser)

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                LOG.info("Skeleton loaded with status: $httpStatusCode")
                isSkeletonLoaded = true
                ApplicationManager.getApplication().invokeLater {
                    val text = runReadAction { FileDocumentManager.getInstance().getDocument(file)?.text }
                    if (text != null) {
                        updateAlarm.cancelAllRequests()
                        updateAlarm.addRequest({
                            updatePreview(text)
                        }, 0)
                    }
                }
            }
        }, browser.cefBrowser)

        val document = runReadAction { FileDocumentManager.getInstance().getDocument(file) }
        if (document != null) {
            val initialText = runReadAction { document.text }
            updatePreview(initialText)

            documentListener = object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    updateAlarm.cancelAllRequests()
                    updateAlarm.addRequest({
                        updatePreview(event.document.text)
                    }, 300)
                }
            }
            document.addDocumentListener(documentListener!!)
        }
    }

    private fun updateImageEditor(svg: String) {
        LOG.info("Updating image editor, SVG length: ${svg.length}")
        if (svg.isBlank()) {
            LOG.warn("SVG is blank, skipping update")
            return
        }

        val fileName = "${file.nameWithoutExtension}.svg"
        val svgFileType = FileTypeManager.getInstance().getFileTypeByExtension("svg")

        val existingEditor = currentImageEditor
        val existingFile = tempSvgFile

        if (existingEditor != null && existingFile != null && existingEditor.isValid) {
            LOG.info("Reusing existing image editor of type: ${existingEditor.javaClass.name}")
            ApplicationManager.getApplication().runWriteAction {
                try {
                    val document = FileDocumentManager.getInstance().getDocument(existingFile)
                    if (document != null) {
                        LOG.info("Updating SVG document")
                        document.setText(svg)
                    } else {
                        LOG.info("Updating SVG binary content")
                        existingFile.setBinaryContent(
                            svg.toByteArray(Charsets.UTF_8),
                            LocalTimeCounter.currentTime(),
                            System.currentTimeMillis()
                        )
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to update existing SVG file", e)
                }
            }
            FileContentUtilCore.reparseFiles(existingFile)

            // Try to force reload via reflection if it's an ImageEditor
            refreshImageEditor(existingEditor, existingFile)

            val state = existingEditor.getState(FileEditorStateLevel.FULL)
            existingEditor.setState(state)
            return
        }

        val newFile = LightVirtualFile(fileName, svgFileType, svg)
        tempSvgFile = newFile

        val providers = FileEditorProviderManager.getInstance().getProviderList(project, newFile)
        LOG.info("Found ${providers.size} providers for temp SVG file: ${providers.joinToString { it.getEditorTypeId() }}")
        val imageProvider = providers.firstOrNull {
            it.getEditorTypeId() == "images" || it.javaClass.name.contains(
                "Image",
                ignoreCase = true
            )
        }

        if (imageProvider != null) {
            LOG.info("Using image provider: ${imageProvider.javaClass.name}")
            val oldEditor = currentImageEditor
            val newEditor = imageProvider.createEditor(project, newFile)

            if (oldEditor != null) {
                val state = oldEditor.getState(FileEditorStateLevel.FULL)
                newEditor.setState(state)
            }

            previewPanel.removeAll()
            previewPanel.add(newEditor.component, BorderLayout.CENTER)
            previewPanel.revalidate()
            previewPanel.repaint()

            currentImageEditor = newEditor
            if (oldEditor != null) {
                Disposer.dispose(oldEditor)
            }
        } else {
            LOG.error("No image provider found for SVG. Providers found: ${providers.joinToString { it.getEditorTypeId() }}")
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
                errorLabel.isVisible = false
                file.putUserData(MERMAID_ERROR_KEY, null)
                val psiFile = runReadAction { PsiManager.getInstance(project).findFile(file) }
                if (psiFile != null) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
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

                    // Attempt to parse
                    try {
                        await mermaid.parse(text);
                    } catch (err) {
                        console.error(err);
                        // Mermaid v11 parse errors might be an array or a single object
                        const errorList = Array.isArray(err) ? err : [err];
                        const errorData = {
                            errors: errorList.map(e => ({
                                message: e.message || e.toString(),
                                line: e.hash ? e.hash.line : (e.loc ? e.loc.first_line : (e.line !== undefined ? e.line : -1)),
                                column: e.hash ? (e.hash.loc ? e.hash.loc.first_column : -1) : (e.loc ? e.loc.first_column : (e.column !== undefined ? e.column : -1))
                            }))
                        };
                        ${errorJsQuery.inject("JSON.stringify(errorData)")}
                        return;
                    }
                    
                    // If parse succeeded, try to render
                    const id = 'mermaid-svg-' + Date.now();
                    const container = document.getElementById('mermaid-container');
                    const { svg } = await mermaid.render(id, text, container);
                    ${jsQuery.inject("svg")}
                } catch (err) {
                    console.error(err);
                    const errorData = {
                        errors: [{
                            message: err.message || err.toString(),
                            line: err.hash ? err.hash.line : (err.loc ? err.loc.first_line : (err.line !== undefined ? err.line : -1)),
                            column: err.hash ? (err.hash.loc ? err.hash.loc.first_column : -1) : (err.loc ? err.loc.first_column : (err.column !== undefined ? err.column : -1))
                        }]
                    };
                    ${errorJsQuery.inject("JSON.stringify(errorData)")}
                }
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    private fun loadSkeleton(initialText: String) {
        lastMermaidJsUrl = MermaidSettingsState.instance.mermaidJsUrl
        val escapedInitialText = StringUtil.escapeStringCharacters(initialText)

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <script>
                    function reportError(msg) {
                        const errorData = {
                            errors: [{ message: msg, line: -1, column: -1 }]
                        };
                        ${errorJsQuery.inject("JSON.stringify(errorData)")}
                    }
                </script>
                <script src="${lastMermaidJsUrl}" onerror="reportError('Failed to load Mermaid.js from ' + this.src)"></script>
                <script>
                    document.addEventListener("DOMContentLoaded", function() {
                        try {
                            mermaid.initialize({
                                startOnLoad: false,
                                theme: (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'default',
                                securityLevel: 'loose'
                            });
                            
                            // 초기 렌더링
                            const initialText = '$escapedInitialText';
                            if (initialText) {
                                (async () => {
                                    try {
                                        // Attempt to parse
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
                                            ${errorJsQuery.inject("JSON.stringify(errorData)")}
                                            return;
                                        }
                                        
                                        const id = 'mermaid-svg-' + Date.now();
                                        const container = document.getElementById('mermaid-container');
                                        const { svg } = await mermaid.render(id, initialText, container);
                                        ${jsQuery.inject("svg")}
                                    } catch (err) {
                                        console.error(err);
                                        const errorData = {
                                            errors: [{
                                                message: err.message || err.toString(),
                                                line: err.hash ? err.hash.line : (err.loc ? err.loc.first_line : (err.line !== undefined ? err.line : -1)),
                                                column: err.hash ? (err.hash.loc ? err.hash.loc.first_column : -1) : (err.loc ? err.loc.first_column : (err.column !== undefined ? err.column : -1))
                                            }]
                                        };
                                        ${errorJsQuery.inject("JSON.stringify(errorData)")}
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
                            ${errorJsQuery.inject("JSON.stringify(errorData)")}
                        }
                    });
                </script>
            </head>
            <body>
                <div id="mermaid-container"></div>
            </body>
            </html>
        """.trimIndent()
        isSkeletonLoaded = false
        browser.loadHTML(html)
    }

    private fun refreshImageEditor(editor: FileEditor, file: VirtualFile) {
        val component = editor.component
        val imageEditor = findImageEditorComponent(component)
        if (imageEditor != null) {
            try {
                val setFileMethod = imageEditor.javaClass.getMethod("setFile", VirtualFile::class.java)
                setFileMethod.isAccessible = true
                setFileMethod.invoke(imageEditor, file)
                LOG.info("Refreshed ImageEditor via reflection")
            } catch (e: Exception) {
                LOG.warn("Failed to refresh ImageEditor via reflection: ${e.message}")
            }
        }
    }

    private fun findImageEditorComponent(comp: java.awt.Component): Any? {
        if (comp.javaClass.name.contains("ImageEditor") || comp.javaClass.name.contains("SvgViewer")) {
            return comp
        }
        if (comp is java.awt.Container) {
            for (child in comp.components) {
                val found = findImageEditorComponent(child)
                if (found != null) return found
            }
        }
        return null
    }

    override fun getFile(): VirtualFile = file

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = component

    override fun getName(): String = "Mermaid Preview"

    override fun setState(state: FileEditorState) {
        currentImageEditor?.setState(state)
    }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        currentImageEditor?.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        currentImageEditor?.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = currentImageEditor?.getCurrentLocation()

    override fun dispose() {
        val document = runReadAction { FileDocumentManager.getInstance().getDocument(file) }
        documentListener?.let {
            document?.removeDocumentListener(it)
        }
        currentImageEditor?.let {
            Disposer.dispose(it)
        }
        jsQuery.dispose()
        errorJsQuery.dispose()
        browser.dispose()
    }
}
