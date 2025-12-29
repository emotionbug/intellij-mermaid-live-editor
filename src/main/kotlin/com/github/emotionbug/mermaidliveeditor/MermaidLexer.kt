package com.github.emotionbug.mermaidliveeditor

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import java.util.regex.Pattern

class MermaidLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var currentToken: IElementType? = null

    private val DIAGRAM_TYPES = setOf(
        "graph", "flowchart", "sequenceDiagram", "classDiagram", "stateDiagram", "stateDiagram-v2",
        "erDiagram", "gantt", "pie", "gitGraph", "requirementDiagram", "journey", "timeline", "mindmap"
    )

    private val DIRECTIONS = setOf("TD", "TB", "BT", "RL", "LR")

    private val KEYWORDS = setOf(
        "subgraph", "end", "click", "callback", "style", "classDef", "class", "direction",
        "participant", "actor", "boundary", "control", "entity", "database", "collections",
        "notes", "note", "over", "as", "rect", "autonumber", "loop", "alt", "else", "opt", "parallel", "and", "critical", "break",
        "title", "section", "dateFormat", "axisFormat", "todayMarker", "excludes", "includes",
        "state", "join", "fork", "choice", "PK", "FK",
        "commit", "branch", "checkout", "merge", "tag", "cherry-pick", "reset", "revert",
        "abstract", "static", "public", "private", "protected", "package", "namespace"
    )

    private val PATTERNS = listOf(
        Pair(MermaidTokenTypes.COMMENT, Pattern.compile("^%%.*")),
        Pair(MermaidTokenTypes.WHITE_SPACE, Pattern.compile("^\\s+")),
        Pair(MermaidTokenTypes.STRING, Pattern.compile("^\"[^\"]*\"")),
        Pair(MermaidTokenTypes.ARROW, Pattern.compile("^(?:--+>|--+|->+|==+>|==+|-\\.-?>|<-+>|<-+|<--+|<-+|--|==|\\.\\.|-)")),
        Pair(MermaidTokenTypes.BRACKET, Pattern.compile("^[\\[\\](){}:,]|[\\[\\(]{2,}|[\\]\\)]{2,}")),
        Pair(MermaidTokenTypes.IDENTIFIER, Pattern.compile("^[a-zA-Z\\-0-9_]+"))
    )

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = currentToken

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        if (tokenEnd >= endOffset) {
            currentToken = null
            tokenStart = tokenEnd
            return
        }

        tokenStart = tokenEnd
        val remaining = buffer.subSequence(tokenStart, endOffset)

        for ((type, pattern) in PATTERNS) {
            val matcher = pattern.matcher(remaining)
            if (matcher.find()) {
                val match = matcher.group()
                tokenEnd = tokenStart + match.length
                
                if (type == MermaidTokenTypes.IDENTIFIER) {
                    currentToken = when {
                        DIAGRAM_TYPES.contains(match) -> MermaidTokenTypes.DIAGRAM_TYPE
                        DIRECTIONS.contains(match) -> MermaidTokenTypes.DIRECTION
                        KEYWORDS.contains(match) -> MermaidTokenTypes.KEYWORD
                        else -> MermaidTokenTypes.NODE_ID // Default to NODE_ID for better highlighting
                    }
                } else {
                    currentToken = type
                }
                return
            }
        }

        // Catch-all for single characters
        tokenEnd = tokenStart + 1
        currentToken = MermaidTokenTypes.BAD_CHARACTER
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset
}
