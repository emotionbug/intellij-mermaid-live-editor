package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.util.Key

data class MermaidError(
    val message: String,
    val line: Int = -1,
    val column: Int = -1
)

data class MermaidErrorData(
    val errors: List<MermaidError>
)

val MERMAID_ERROR_KEY = Key.create<MermaidErrorData>("MERMAID_ERROR_KEY")
