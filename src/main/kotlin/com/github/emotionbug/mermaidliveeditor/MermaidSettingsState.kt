package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

fun interface MermaidSettingsListener {
    fun settingsChanged()
}

enum class MermaidJsSource {
    BUILT_IN,
    CDN,
    LOCAL_FILE
}

@State(
    name = "com.github.emotionbug.mermaidliveeditor.MermaidSettingsState",
    storages = [Storage("MermaidLiveEditorSettings.xml")]
)
class MermaidSettingsState : PersistentStateComponent<MermaidSettingsState> {
    var jsSource: MermaidJsSource = MermaidJsSource.BUILT_IN
    var mermaidJsUrl: String = ""

    override fun getState(): MermaidSettingsState = this

    override fun loadState(state: MermaidSettingsState) {
        jsSource = state.jsSource
        mermaidJsUrl = state.mermaidJsUrl
    }

    companion object {
        const val MERMAID_JS_DEFAULT_NAME = "mermaid_11.12.0.min.js"

        val TOPIC = Topic.create("Mermaid Settings Changed", MermaidSettingsListener::class.java)

        val instance: MermaidSettingsState
            get() = ApplicationManager.getApplication().getService(MermaidSettingsState::class.java)
    }
}
