package com.github.emotionbug.mermaidliveeditor.editor.browser

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter

class MermaidBrowserManager(parentDisposable: Disposable) : Disposable {
    private val LOG = Logger.getInstance(MermaidBrowserManager::class.java)
    
    val browser = JBCefBrowser()
    val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    val errorJsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    init {
        Disposer.register(parentDisposable, this)
        setupHandlers()
    }

    private fun setupHandlers() {
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
                    CefSettings.LogSeverity.LOGSEVERITY_ERROR, CefSettings.LogSeverity.LOGSEVERITY_FATAL -> LOG.error(logMsg)
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
                    this@MermaidBrowserManager.browser.openDevtools()
                    return true
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
