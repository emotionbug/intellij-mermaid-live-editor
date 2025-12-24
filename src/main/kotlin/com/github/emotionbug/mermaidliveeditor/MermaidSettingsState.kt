package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "com.github.emotionbug.mermaidliveeditor.MermaidSettingsState",
    storages = [Storage("MermaidLiveEditorSettings.xml")]
)
class MermaidSettingsState : PersistentStateComponent<MermaidSettingsState> {
    var mermaidJsUrl: String = "https://cdnjs.cloudflare.com/ajax/libs/mermaid/11.12.0/mermaid.min.js"

    override fun getState(): MermaidSettingsState = this

    override fun loadState(state: MermaidSettingsState) {
        mermaidJsUrl = state.mermaidJsUrl
    }

    companion object {
        val instance: MermaidSettingsState
            get() = ApplicationManager.getApplication().getService(MermaidSettingsState::class.java)
    }
}
