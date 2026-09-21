package ai.saffron.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private class RunRow(
    val label: String,
    val detail: String = "",
    val icon: Icon? = null,
    val tooltip: String? = null,
    val attention: StatusAttention? = null,
) {
    override fun toString(): String = label
}

/**
 * What the last run left to look at: failed scenarios, then healed ones.
 * Double-click opens the screenshot of the page at the moment it went wrong,
 * in the IDE's image viewer, which usually answers "why" faster than the
 * error text. Without a screenshot, it opens the scenario.
 */
class LastRunTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val root = DefaultMutableTreeNode(RunRow("Saffron"))
    private val model = DefaultTreeModel(root)
    private var features: List<StatusFeature> = emptyList()
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val r = (value as? DefaultMutableTreeNode)?.userObject as? RunRow ?: return
                icon = r.icon
                append(r.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (r.detail.isNotEmpty()) append("  ${r.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = r.tooltip
            }
        }
    }

    init {
        toolbar = toolbar(
            action("Open Screenshot", "The page at the moment the selected scenario went wrong", AllIcons.FileTypes.Image) { openScreenshot() },
            action("Open Scenario", "Jump to the selected scenario in its feature file", AllIcons.Actions.EditSource) { openScenario() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
        tree.emptyText.text = "Loading…"
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openScreenshot() || openScenario()
        }.installOn(tree)
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        root.removeAllChildren()
        features = status?.features ?: emptyList()
        val run = status?.lastRun
        val attention = run?.attention
        tree.emptyText.text = when {
            status == null -> "Loading…"
            run == null -> "No run yet"
            // Saying "everything passed" would be a claim this data cannot support.
            attention == null -> "Update saffron-ai to the release with failure screenshots"
            else -> "Everything passed: ${run.totals.green} green in the last run"
        }
        for ((state, label, icon) in listOf(
            Triple("red", "Failed", AllIcons.General.Error),
            Triple("yellow", "Healed, pending review", AllIcons.General.Warning),
        )) {
            val group = attention.orEmpty().filter { it.status == state }
            if (group.isEmpty()) continue
            val parent = DefaultMutableTreeNode(RunRow(label, "${group.size}", AllIcons.Nodes.Folder))
            for (a in group) {
                val shot = a.evidence.firstOrNull()
                parent.add(
                    DefaultMutableTreeNode(
                        RunRow(
                            a.scenario,
                            a.failedStep ?: a.feature,
                            if (shot != null) AllIcons.FileTypes.Image else icon,
                            listOfNotNull(
                                "${a.feature} › ${a.scenario}",
                                a.failedStep?.let { "Failed step: $it" },
                                a.error,
                                if (shot != null) "Double-click to open the screenshot." else "No screenshot for this one; double-click opens the scenario.",
                            ).joinToString("\n"),
                            a,
                        ),
                    ),
                )
            }
            root.add(parent)
        }
        model.reload()
        TreeUtil.expandAll(tree)
    }

    private fun selected(): StatusAttention? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? RunRow)?.attention

    private fun openScreenshot(): Boolean {
        val shot = selected()?.evidence?.firstOrNull() ?: return false
        val base = project.basePath ?: return false
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/${shot.file}")
        if (vf == null) {
            note.text = "That screenshot is gone. Screenshots are kept for the latest run only."
            return false
        }
        FileEditorManager.getInstance(project).openFile(vf, true)
        return true
    }

    private fun openScenario(): Boolean {
        val a = selected() ?: return false
        val base = project.basePath ?: return false
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/${a.feature}") ?: return false
        val line = features.firstOrNull { it.path == a.feature }?.scenarios?.firstOrNull { it.name == a.baseScenario }?.line
        OpenFileDescriptor(project, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }
}
