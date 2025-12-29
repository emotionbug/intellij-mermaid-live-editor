package com.github.emotionbug.mermaidliveeditor

import com.intellij.lang.Language

object MermaidLanguage : Language("Mermaid") {
    private fun readResolve(): Any = MermaidLanguage
}
