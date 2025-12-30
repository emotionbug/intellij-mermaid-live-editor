package com.github.emotionbug.mermaidliveeditor.editor.browser

import com.github.emotionbug.mermaidliveeditor.editor.actions.SavePptxAction
import com.github.emotionbug.mermaidliveeditor.editor.actions.SaveSvgAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.net.URLConnection
import javax.swing.SwingUtilities

class MermaidBrowserManager(
    private val project: Project,
    parentDisposable: Disposable,
    private val svgGetter: () -> String?
) : Disposable {
    private val LOG = Logger.getInstance(MermaidBrowserManager::class.java)

    val browser = JBCefBrowser()
    val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    val errorJsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    init {
        Disposer.register(parentDisposable, this)
        setupHandlers()
    }

    private fun setupHandlers() {
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest?,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String?,
                disableDefaultHandling: BoolRef?
            ): CefResourceRequestHandler? {
                val url = request?.url ?: return null
                if (url.startsWith("https://mermaid-preview/")) {
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun getResourceHandler(
                            browser: CefBrowser?,
                            frame: CefFrame?,
                            request: CefRequest?
                        ): CefResourceHandler? {
                            return MermaidResourceHandler()
                        }
                    }
                }
                return null
            }
        }, browser.cefBrowser)

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
                model?.clear()
                model?.addItem(CefMenuModel.MenuId.MENU_ID_USER_FIRST, "Save as SVG")
                model?.addItem(CefMenuModel.MenuId.MENU_ID_USER_FIRST + 1, "Save as PPTX")
                model?.addSeparator()
                model?.addItem(CefMenuModel.MenuId.MENU_ID_USER_FIRST + 3, "Reset View")
                model?.addSeparator()
                model?.addItem(CefMenuModel.MenuId.MENU_ID_USER_FIRST + 2, "Open DevTools")
            }

            override fun onContextMenuCommand(
                browser: CefBrowser?,
                frame: CefFrame?,
                params: CefContextMenuParams?,
                commandId: Int,
                eventFlags: Int
            ): Boolean {
                when (commandId) {
                    CefMenuModel.MenuId.MENU_ID_USER_FIRST -> {
                        ApplicationManager.getApplication().invokeLater {
                            val group = DefaultActionGroup()
                            group.add(SaveSvgAction(project, svgGetter))
                            val popupMenu =
                                ActionManager.getInstance().createActionPopupMenu("MermaidBrowserPopupMenu", group)
                            val component = this@MermaidBrowserManager.browser.component
                            val pointerInfo = java.awt.MouseInfo.getPointerInfo()
                            if (pointerInfo != null) {
                                val location = pointerInfo.location
                                SwingUtilities.convertPointFromScreen(location, component)
                                popupMenu.component.show(component, location.x, location.y)
                            }
                        }
                        return true
                    }

                    CefMenuModel.MenuId.MENU_ID_USER_FIRST + 1 -> {
                        ApplicationManager.getApplication().invokeLater {
                            val group = DefaultActionGroup()
                            group.add(SavePptxAction(project, svgGetter))
                            val popupMenu =
                                ActionManager.getInstance().createActionPopupMenu("MermaidBrowserPopupMenu", group)
                            val component = this@MermaidBrowserManager.browser.component
                            val pointerInfo = java.awt.MouseInfo.getPointerInfo()
                            if (pointerInfo != null) {
                                val location = pointerInfo.location
                                SwingUtilities.convertPointFromScreen(location, component)
                                popupMenu.component.show(component, location.x, location.y)
                            }
                        }
                        return true
                    }

                    CefMenuModel.MenuId.MENU_ID_USER_FIRST + 3 -> {
                        this@MermaidBrowserManager.browser.cefBrowser.executeJavaScript(
                            "if (window.resetView) window.resetView();",
                            "",
                            0
                        )
                        return true
                    }

                    CefMenuModel.MenuId.MENU_ID_USER_FIRST + 2 -> {
                        this@MermaidBrowserManager.browser.openDevtools()
                        return true
                    }
                }
                return false
            }
        }, browser.cefBrowser)
    }

    override fun dispose() {
        jsQuery.dispose()
        errorJsQuery.dispose()
        browser.dispose()
    }
}

class MermaidResourceHandler : CefResourceHandlerAdapter() {
    private var data: ByteArray? = null
    private var offset = 0
    private var mimeType: String? = null

    override fun processRequest(request: CefRequest?, callback: org.cef.callback.CefCallback?): Boolean {
        val url = request?.url ?: return false
        val path = url.removePrefix("https://mermaid-preview/").ifEmpty { "mermaid_preview.html" }
        val resourcePath = if (path.startsWith("/")) path else "/$path"

        val stream = javaClass.getResourceAsStream(resourcePath)
        if (stream != null) {
            data = stream.use { it.readBytes() }
            mimeType = URLConnection.guessContentTypeFromName(resourcePath) ?: "text/html"
            callback?.Continue()
            return true
        }
        return false
    }

    override fun getResponseHeaders(
        response: org.cef.network.CefResponse?,
        responseLength: org.cef.misc.IntRef?,
        redirectUrl: org.cef.misc.StringRef?
    ) {
        response?.mimeType = mimeType
        response?.status = 200
        responseLength?.set(data?.size ?: 0)
    }

    override fun readResponse(
        dataOut: ByteArray?,
        bytesToRead: Int,
        bytesRead: org.cef.misc.IntRef?,
        callback: org.cef.callback.CefCallback?
    ): Boolean {
        val available = (data?.size ?: 0) - offset
        if (available <= 0) {
            bytesRead?.set(0)
            return false
        }

        val toRead = minOf(available, bytesToRead)
        data?.copyInto(dataOut!!, 0, offset, offset + toRead)
        offset += toRead
        bytesRead?.set(toRead)
        return true
    }

    override fun cancel() {
    }
}
