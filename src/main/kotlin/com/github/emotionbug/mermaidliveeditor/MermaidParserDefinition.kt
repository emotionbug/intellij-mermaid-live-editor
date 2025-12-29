package com.github.emotionbug.mermaidliveeditor

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase

class MermaidParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = MermaidLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val mark = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        mark.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRINGS

    override fun createElement(node: ASTNode?): PsiElement = ASTWrapperPsiElement(node!!)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = MermaidFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?): ParserDefinition.SpaceRequirements {
        return ParserDefinition.SpaceRequirements.MAY
    }
}

class MermaidFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, MermaidLanguage) {
    override fun getFileType(): com.intellij.openapi.fileTypes.FileType = MermaidFileType
    override fun toString(): String = "Mermaid File"
}

val FILE = IFileElementType(MermaidLanguage)
val COMMENTS = TokenSet.create(MermaidTokenTypes.COMMENT)
val STRINGS = TokenSet.create(MermaidTokenTypes.STRING)