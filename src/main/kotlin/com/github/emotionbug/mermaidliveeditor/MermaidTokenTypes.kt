package com.github.emotionbug.mermaidliveeditor

import com.intellij.psi.tree.IElementType
import com.intellij.psi.TokenType

interface MermaidTokenTypes {
    companion object {
        val KEYWORD = IElementType("KEYWORD", MermaidLanguage)
        val DIAGRAM_TYPE = IElementType("DIAGRAM_TYPE", MermaidLanguage)
        val DIRECTION = IElementType("DIRECTION", MermaidLanguage)
        val COMMENT = IElementType("COMMENT", MermaidLanguage)
        val STRING = IElementType("STRING", MermaidLanguage)
        val IDENTIFIER = IElementType("IDENTIFIER", MermaidLanguage)
        val ARROW = IElementType("ARROW", MermaidLanguage)
        val BRACKET = IElementType("BRACKET", MermaidLanguage)
        val NODE_ID = IElementType("NODE_ID", MermaidLanguage)
        val BAD_CHARACTER = TokenType.BAD_CHARACTER
        val WHITE_SPACE = TokenType.WHITE_SPACE
    }
}
