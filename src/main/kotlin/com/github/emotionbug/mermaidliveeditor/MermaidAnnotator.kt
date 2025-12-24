package com.github.emotionbug.mermaidliveeditor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class MermaidAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return

        val file = element.virtualFile ?: return
        val errorData = file.getUserData(MERMAID_ERROR_KEY) ?: return

        val document = element.viewProvider.document ?: return

        for (error in errorData.errors) {
            try {
                // Mermaid errors can be 1-based or 0-based depending on the parser
                // or sometimes -1 if not available.
                if (error.line >= 0) {
                    // Try to treat as 1-based first if it's > 0, otherwise as 0-based.
                    // Actually, most IDE documents are 0-based.
                    // If we get line=1, we want line index 0.
                    // If we get line=0, we also probably want line index 0.
                    val lineIndex = if (error.line > 0) error.line - 1 else 0
                    val line = lineIndex.coerceIn(0, document.lineCount - 1)
                    val lineStartOffset = document.getLineStartOffset(line)
                    val lineEndOffset = document.getLineEndOffset(line)

                    val startOffset = if (error.column > 0) {
                        (lineStartOffset + error.column - 1).coerceAtMost(lineEndOffset)
                    } else if (error.column == 0) {
                        lineStartOffset
                    } else {
                        lineStartOffset
                    }

                    val endOffset = if (error.column > 0) {
                        (startOffset + 1).coerceAtMost(lineEndOffset)
                    } else {
                        lineEndOffset
                    }

                    if (startOffset < endOffset) {
                        holder.newAnnotation(HighlightSeverity.ERROR, error.message)
                            .range(TextRange(startOffset, endOffset))
                            .create()
                    } else if (lineStartOffset < lineEndOffset) {
                        holder.newAnnotation(HighlightSeverity.ERROR, error.message)
                            .range(TextRange(lineStartOffset, lineEndOffset))
                            .create()
                    } else {
                        holder.newAnnotation(HighlightSeverity.ERROR, error.message)
                            .range(element.textRange)
                            .create()
                    }
                } else {
                    holder.newAnnotation(HighlightSeverity.ERROR, error.message)
                        .range(element.textRange)
                        .create()
                }
            } catch (e: Exception) {
                // Range errors can happen if document changed
            }
        }
    }
}
