package com.github.emotionbug.mermaidliveeditor.editor.actions

import com.github.emotionbug.mermaidliveeditor.MermaidSvg2Pptx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger

class SaveSvgAction(private val project: Project, private val lastSvgProvider: () -> String?) : AnAction("Save SVG As...") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val svg = lastSvgProvider() ?: return
        val descriptor = FileSaverDescriptor("Save SVG As", "Save the rendered diagram as an SVG file", "svg")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val fileWrapper = dialog.save(null as VirtualFile?, "diagram.svg")
        if (fileWrapper != null) {
            WriteAction.run<Exception> {
                FileUtil.writeToFile(fileWrapper.file, svg)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = lastSvgProvider() != null
    }
}

class SavePptxAction(private val project: Project, private val lastSvgProvider: () -> String?) : AnAction("Save PPTX As...") {
    private val LOG = Logger.getInstance(SavePptxAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val svg = lastSvgProvider() ?: return
        val descriptor = FileSaverDescriptor("Save PPTX As", "Save the rendered diagram as a PPTX file", "pptx")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val fileWrapper = dialog.save(null as VirtualFile?, "diagram.pptx")
        if (fileWrapper != null) {
            try {
                MermaidSvg2Pptx.generate(svg, fileWrapper.file)
            } catch (e: Exception) {
                LOG.error("Failed to generate PPTX", e)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = lastSvgProvider() != null
    }
}
