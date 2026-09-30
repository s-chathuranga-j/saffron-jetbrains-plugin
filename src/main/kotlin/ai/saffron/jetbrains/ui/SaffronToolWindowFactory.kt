package ai.saffron.jetbrains.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.nio.file.Path
import javax.swing.JComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Saffron tool window (right side): Files, Last Run, Proposals, Tags, Health,
 * Orphans and Dashboard tabs. Files scans the project directly (works without the npm
 * package); the other tabs read `saffron status --json`.
 */
class SaffronToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean =
        withContext(Dispatchers.IO) { SaffronStatusService.getInstance(project).discoverRoots().isNotEmpty() }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val parent = toolWindow.disposable
        val manager = toolWindow.contentManager
        manager.addContent(factory.createContent(SaffronPanel(project, parent), "Files", false))
        manager.addContent(factory.createContent(LastRunTab(project, parent), "Last Run", false))
        manager.addContent(factory.createContent(ProposalsTab(project, parent), "Proposals", false))
        manager.addContent(factory.createContent(TagsTab(project, parent), "Tags", false))
        manager.addContent(factory.createContent(HealthTab(project, parent), "Health", false))
        manager.addContent(factory.createContent(OrphansTab(project, parent), "Orphans", false))
        manager.addContent(factory.createContent(DashboardTab(project, parent), "Dashboard", false))
        toolWindow.setTitleActions(listOf(RootChooser(project)))
    }
}

/** Which Saffron project the tabs show, when the IDE root holds several. Hidden when there is one. */
private class RootChooser(private val project: Project) : ComboBoxAction(), DumbAware {

    private val service get() = SaffronStatusService.getInstance(project)

    private fun label(root: Path): String {
        val base = project.basePath ?: return root.toString()
        return FileUtil.getRelativePath(base, root.toString(), '/')?.takeIf { it != "." && !it.startsWith("..") } ?: root.fileName?.toString() ?: root.toString()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = service.roots.size > 1
        e.presentation.text = label(service.root)
        e.presentation.description = "The Saffron project the tabs show"
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup =
        DefaultActionGroup(service.roots.map { root ->
            object : DumbAwareAction(label(root)) {
                override fun actionPerformed(e: AnActionEvent) = service.choose(root)
            }
        })
}
