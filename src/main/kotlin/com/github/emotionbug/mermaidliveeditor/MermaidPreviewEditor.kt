package com.github.emotionbug.mermaidliveeditor

import com.google.gson.Gson
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
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
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
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
    private val svgBrowser = JBCefBrowser()
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val errorJsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var documentListener: DocumentListener? = null
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var isSkeletonLoaded = false
    private var lastMermaidJsUrl: String? = null
    private val gson = Gson()
    private var currentImageEditor: FileEditor? = null
    private var tempSvgFile: LightVirtualFile? = null
    private var lastSvg: String? = null

    private val contextMenuListener = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            showPopupMenu(e)
        }

        override fun mouseReleased(e: MouseEvent) {
            showPopupMenu(e)
        }

        private fun showPopupMenu(e: MouseEvent) {
            if (e.isPopupTrigger) {
                val actionManager = ActionManager.getInstance()
                val group = DefaultActionGroup()

                group.add(object : AnAction("Save SVG As...") {
                    override fun getActionUpdateThread(): ActionUpdateThread {
                        return ActionUpdateThread.BGT
                    }

                    override fun actionPerformed(e: AnActionEvent) {
                        val svg = lastSvg ?: return
                        val descriptor =
                            FileSaverDescriptor("Save SVG As", "Save the rendered diagram as an SVG file", "svg")
                        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                        val fileWrapper = dialog.save(null as VirtualFile?, "diagram.svg")
                        if (fileWrapper != null) {
                            WriteAction.run<Exception> {
                                FileUtil.writeToFile(fileWrapper.file, svg)
                            }
                        }
                    }

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = lastSvg != null
                    }
                })

                group.add(object : AnAction("Save PPTX As...") {
                    override fun getActionUpdateThread(): ActionUpdateThread {
                        return ActionUpdateThread.BGT
                    }

                    override fun actionPerformed(e: AnActionEvent) {
                        val svg = lastSvg ?: return
                        val descriptor =
                            FileSaverDescriptor("Save PPTX As", "Save the rendered diagram as a PPTX file", "pptx")
                        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                        val fileWrapper = dialog.save(null as VirtualFile?, "diagram.pptx")
                        if (fileWrapper != null) {
                            try {
                                MermaidSvg2Pptx.generate(svg, fileWrapper.file)
                            } catch (e: Exception) {
                                LOG.error("Failed to generate PPTX", e)
                            }
                        }
                    }

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = lastSvg != null
                    }
                })

                val popupMenu = actionManager.createActionPopupMenu("MermaidPreviewPopupMenu", group)
                popupMenu.component.show(e.component, e.x, e.y)
            }
        }
    }

    private fun addContextMenuRecursively(component: Component) {
        component.addMouseListener(contextMenuListener)
        if (component is Container) {
            for (child in component.components) {
                addContextMenuRecursively(child)
            }
        }
    }

    init {
        // Browser must be in the component tree for JCEF to work correctly in some cases
        // We use a layered pane to hide it without affecting the layout
        val layeredPane = JLayeredPane()
        layeredPane.layout = null // Manual positioning

        val browserComponent = browser.component
        browserComponent.bounds = Rectangle(0, 0, 0, 0)
        browserComponent.isVisible = false
        layeredPane.add(browserComponent, JLayeredPane.DEFAULT_LAYER)

        val tmpSvgBrowserComponent = svgBrowser.component
        tmpSvgBrowserComponent.bounds = Rectangle(0, 0, 0, 0)
        tmpSvgBrowserComponent.isVisible = false
        layeredPane.add(tmpSvgBrowserComponent, JLayeredPane.DEFAULT_LAYER)

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(errorLabel, BorderLayout.NORTH)
        mainPanel.add(previewPanel, BorderLayout.CENTER)

        mainPanel.bounds = Rectangle(0, 0, 0, 0) // Will be managed by component layout
        layeredPane.add(mainPanel, JLayeredPane.PALETTE_LAYER)

        // Make layeredPane follow parent size
        component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                val r = Rectangle(0, 0, component.width, component.height)
                layeredPane.bounds = r
                mainPanel.bounds = r
                layeredPane.revalidate()
                layeredPane.repaint()
            }
        })

        component.add(layeredPane, BorderLayout.CENTER)

        jsQuery.addHandler { svg ->
            ApplicationManager.getApplication().invokeLater {
                lastSvg = svg
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
        if (svg == lastSvg && currentImageEditor != null) {
            LOG.info("SVG content unchanged, skipping updateImageEditor")
            return
        }
        LOG.info("updateImageEditor called, SVG length: ${svg.length}")
        if (svg.isBlank()) {
            LOG.warn("SVG is blank, skipping update")
            return
        }

        try {
            val svgBytes = svg.toByteArray(Charsets.UTF_8)
            if (tempSvgFile == null) {
                tempSvgFile =
                    LightVirtualFile("preview.svg", FileTypeManager.getInstance().getFileTypeByExtension("svg"), svg)
            } else {
                ApplicationManager.getApplication().runWriteAction {
                    try {
                        tempSvgFile!!.setBinaryContent(svgBytes)
                        // Update modification stamp to trigger refresh using reflection or other means if protected
                        // But wait, setBinaryContent might already update it.
                        // Let's try to just use setBinaryContent and if it doesn't work, we'll find another way.
                        // Actually, we can use a custom LightVirtualFile if needed, but let's try to just remove the direct access.
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
                    addContextMenuRecursively(newEditor.component)
                    currentImageEditor = newEditor

                    if (oldState != null) {
                        try {
                            newEditor.setState(oldState)
                        } catch (e: Exception) {
                            LOG.warn("Failed to restore editor state", e)
                        }
                    }

                    if (oldEditor != null) {
                        Disposer.dispose(oldEditor)
                    }
                }
            }
            previewPanel.revalidate()
            previewPanel.repaint()
        } catch (e: Exception) {
            LOG.error("Failed to update image editor", e)
        }
    }

    private fun updatePreview(text: String) {
        LOG.info("updatePreview called")
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
                        ${errorJsQuery.inject("JSON.stringify(errorData)")};
                        return;
                    }
                
                    // If parse succeeded, try to render
                    const id = 'mermaid-svg-' + Date.now();
                    const container = document.getElementById('mermaid-container');
                    const { svg } = await mermaid.render(id, text, container);
                    ${jsQuery.inject("svg")};
                } catch (err) {
                    console.error(err);
                    const errorData = {
                        errors: [{
                            message: err.message || err.toString(),
                            line: err.hash ? err.hash.line : (err.loc ? err.loc.first_line : (err.line !== undefined ? err.line : -1)),
                            column: err.hash ? (err.hash.loc ? err.hash.loc.first_column : -1) : (err.loc ? err.loc.first_column : (err.column !== undefined ? err.column : -1))
                        }]
                    };
                    ${errorJsQuery.inject("JSON.stringify(errorData)")};
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
                        ${errorJsQuery.inject("JSON.stringify(errorData)")};
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
                                            ${errorJsQuery.inject("JSON.stringify(errorData)")};
                                            return;
                                        }
                                        
                                        const id = 'mermaid-svg-' + Date.now();
                                        const container = document.getElementById('mermaid-container');
                                        const { svg } = await mermaid.render(id, initialText, container);
                                        ${jsQuery.inject("svg")};
                                    } catch (err) {
                                        console.error(err);
                                        const errorData = {
                                            errors: [{
                                                message: err.message || err.toString(),
                                                line: err.hash ? err.hash.line : (err.loc ? err.loc.first_line : (err.line !== undefined ? err.line : -1)),
                                                column: err.hash ? (err.hash.loc ? err.hash.loc.first_column : -1) : (err.loc ? err.loc.first_column : (err.column !== undefined ? err.column : -1))
                                            }]
                                        };
                                        ${errorJsQuery.inject("JSON.stringify(errorData)")};
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
                            ${errorJsQuery.inject("JSON.stringify(errorData)")};
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
        browser.loadHTML(html)
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

    override fun getCurrentLocation(): FileEditorLocation? = currentImageEditor?.currentLocation

    override fun dispose() {
        val document = runReadAction { FileDocumentManager.getInstance().getDocument(file) }
        documentListener?.let {
            document?.removeDocumentListener(it)
        }
        jsQuery.dispose()
        errorJsQuery.dispose()
        browser.dispose()
        currentImageEditor?.let { Disposer.dispose(it) }
    }
}
