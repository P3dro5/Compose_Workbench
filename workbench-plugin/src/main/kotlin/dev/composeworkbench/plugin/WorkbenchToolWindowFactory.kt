package dev.composeworkbench.plugin

// STATUS: WRITTEN, NOT COMPILED. The IntelliJ Platform SDK could not be downloaded in the authoring sandbox.
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.composeworkbench.ui.WorkbenchFactory
import java.io.File

class WorkbenchToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val base = project.basePath?.let { File(it) }
        // Real mode when a project is open; demo mode (clearly bannered) otherwise.
        val panel = if (base != null) WorkbenchFactory.forProject(base)
        else WorkbenchFactory.demo(File(System.getProperty("java.io.tmpdir"), "compose-workbench-demo"))
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}
// Known limitation: patches are written with java.io, so the IDE must refresh the VFS (File > Reload from Disk)
// to see them. A production version should apply patches through WriteCommandAction/Document.
