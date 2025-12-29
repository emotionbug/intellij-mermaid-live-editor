package com.github.emotionbug.mermaidliveeditor

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

class MermaidSyntaxHighlighter : SyntaxHighlighterBase() {
    companion object {
        val KEYWORD = createTextAttributesKey("MERMAID_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val DIAGRAM_TYPE = createTextAttributesKey("MERMAID_DIAGRAM_TYPE", DefaultLanguageHighlighterColors.KEYWORD)
        val DIRECTION = createTextAttributesKey("MERMAID_DIRECTION", DefaultLanguageHighlighterColors.CONSTANT)
        val COMMENT = createTextAttributesKey("MERMAID_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val STRING = createTextAttributesKey("MERMAID_STRING", DefaultLanguageHighlighterColors.STRING)
        val ARROW = createTextAttributesKey("MERMAID_ARROW", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val BRACKET = createTextAttributesKey("MERMAID_BRACKET", DefaultLanguageHighlighterColors.BRACKETS)
        val IDENTIFIER = createTextAttributesKey("MERMAID_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
        val NODE_ID = createTextAttributesKey("MERMAID_NODE_ID", DefaultLanguageHighlighterColors.LABEL)

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val DIAGRAM_TYPE_KEYS = arrayOf(DIAGRAM_TYPE)
        private val DIRECTION_KEYS = arrayOf(DIRECTION)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val STRING_KEYS = arrayOf(STRING)
        private val ARROW_KEYS = arrayOf(ARROW)
        private val BRACKET_KEYS = arrayOf(BRACKET)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val NODE_ID_KEYS = arrayOf(NODE_ID)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }

    override fun getHighlightingLexer(): Lexer = MermaidLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        return when (tokenType) {
            MermaidTokenTypes.KEYWORD -> KEYWORD_KEYS
            MermaidTokenTypes.DIAGRAM_TYPE -> DIAGRAM_TYPE_KEYS
            MermaidTokenTypes.DIRECTION -> DIRECTION_KEYS
            MermaidTokenTypes.COMMENT -> COMMENT_KEYS
            MermaidTokenTypes.STRING -> STRING_KEYS
            MermaidTokenTypes.ARROW -> ARROW_KEYS
            MermaidTokenTypes.BRACKET -> BRACKET_KEYS
            MermaidTokenTypes.IDENTIFIER -> IDENTIFIER_KEYS
            MermaidTokenTypes.NODE_ID -> NODE_ID_KEYS
            else -> EMPTY_KEYS
        }
    }
}
