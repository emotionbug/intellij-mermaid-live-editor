package com.github.emotionbug.mermaidliveeditor

import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.editor.colors.TextAttributesKey
import javax.swing.Icon
import com.intellij.icons.AllIcons

class MermaidColorSettingsPage : ColorSettingsPage {
    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", MermaidSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Diagram Type", MermaidSyntaxHighlighter.DIAGRAM_TYPE),
            AttributesDescriptor("Direction", MermaidSyntaxHighlighter.DIRECTION),
            AttributesDescriptor("Comment", MermaidSyntaxHighlighter.COMMENT),
            AttributesDescriptor("String", MermaidSyntaxHighlighter.STRING),
            AttributesDescriptor("Arrow", MermaidSyntaxHighlighter.ARROW),
            AttributesDescriptor("Bracket", MermaidSyntaxHighlighter.BRACKET),
            AttributesDescriptor("Identifier", MermaidSyntaxHighlighter.IDENTIFIER),
            AttributesDescriptor("Node ID", MermaidSyntaxHighlighter.NODE_ID)
        )

        private val TAG_HIGHLIGHTING = mapOf(
            "keyword" to MermaidSyntaxHighlighter.KEYWORD,
            "diagramType" to MermaidSyntaxHighlighter.DIAGRAM_TYPE,
            "direction" to MermaidSyntaxHighlighter.DIRECTION,
            "comment" to MermaidSyntaxHighlighter.COMMENT,
            "string" to MermaidSyntaxHighlighter.STRING,
            "arrow" to MermaidSyntaxHighlighter.ARROW,
            "bracket" to MermaidSyntaxHighlighter.BRACKET,
            "nodeId" to MermaidSyntaxHighlighter.NODE_ID
        )
    }

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Mermaid"

    override fun getIcon(): Icon = AllIcons.FileTypes.Text

    override fun getHighlighter(): SyntaxHighlighter = MermaidSyntaxHighlighter()

    override fun getDemoText(): String = """
        %% <comment>This is a comment</comment>
        <diagramType>graph</diagramType> <direction>TD</direction>
            <nodeId>A</nodeId><bracket>[</bracket>Start<bracket>]</bracket> <arrow>--></arrow> <nodeId>B</nodeId><bracket>{</bracket>Is it working?<bracket>}</bracket>
            <nodeId>B</nodeId> <arrow>--</arrow> Yes <arrow>--></arrow> <nodeId>C</nodeId><bracket>[</bracket>Great!<bracket>]</bracket>
            <nodeId>B</nodeId> <arrow>--</arrow> No <arrow>--></arrow> <nodeId>D</nodeId><bracket>[</bracket>Check <keyword>Lexer</keyword><bracket>]</bracket>
            
            <keyword>subgraph</keyword> Section
                <nodeId>E</nodeId><bracket>[</bracket><string>"Multi-line string"</string><bracket>]</bracket>
            <keyword>end</keyword>
            
        <diagramType>sequenceDiagram</diagramType>
            <keyword>autonumber</keyword>
            <keyword>participant</keyword> Alice
            <keyword>participant</keyword> Bob
            Alice<arrow>->></arrow>Bob: Hello Bob, how are you?
            Bob<arrow>-->></arrow>Alice: I am good thanks!
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAG_HIGHLIGHTING
}
