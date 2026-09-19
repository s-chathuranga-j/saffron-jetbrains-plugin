package ai.saffron.jetbrains.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The Saffron tool window (right side): Files, Proposals, Tags, Health,
 * Orphans and Dashboard tabs. Files scans the project directly (works without the npm
 * package); the other tabs read `saffron status --json`.
 */
class SaffronToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean = SaffronProjectScan.isSaffronProject(project.basePath)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val parent = toolWindow.disposable
        val manager = toolWindow.contentManager
        manager.addContent(factory.createContent(SaffronPanel(project, parent), "Files", false))
        manager.addContent(factory.createContent(ProposalsTab(project, parent), "Proposals", false))
        manager.addContent(factory.createContent(TagsTab(project, parent), "Tags", false))
        manager.addContent(factory.createContent(HealthTab(project, parent), "Health", false))
        manager.addContent(factory.createContent(OrphansTab(project, parent), "Orphans", false))
        manager.addContent(factory.createContent(DashboardTab(project, parent), "Dashboard", false))
    }
}
