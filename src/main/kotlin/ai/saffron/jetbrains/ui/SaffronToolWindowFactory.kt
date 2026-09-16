package ai.saffron.jetbrains.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** The Saffron tool window (right side): list of feature files, run buttons, last-run status. */
class SaffronToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean = SaffronProjectScan.isSaffronProject(project.basePath)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SaffronPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}
