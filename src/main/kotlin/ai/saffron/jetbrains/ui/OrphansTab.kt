package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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

/** One row: label, grey detail, icon, and the file it stands for. */
private class OrphanRow(
    val label: String,
    val detail: String = "",
    val icon: Icon? = null,
    val tooltip: String? = null,
    val file: String? = null,
) {
    override fun toString(): String = label
}

/**
 * Recordings no scenario owns any more.
 *
 * Deleting a scenario, or its whole feature file, leaves the recording
 * behind: nothing replays it, but it stays in the repository, and a
 * reviewer cannot tell it from a live one. The list comes from
 * `saffron status --json`; removing them is `saffron prune --yes`.
 */
class OrphansTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val root = DefaultMutableTreeNode(OrphanRow("Saffron"))
    private val model = DefaultTreeModel(root)
    private var count = 0
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val r = (value as? DefaultMutableTreeNode)?.userObject as? OrphanRow ?: return
                icon = r.icon
                append(r.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (r.detail.isNotEmpty()) append("  ${r.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = r.tooltip
            }
        }
    }

    init {
        toolbar = toolbar(
            action("Remove All", "saffron prune --yes: delete every recording no scenario owns", AllIcons.Actions.GC) { prune() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
        tree.emptyText.text = "Loading…"
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(tree)
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        root.removeAllChildren()
        count = 0
        if (status == null) {
            model.reload()
            return
        }
        val orphans = status.orphans
        if (orphans == null) {
            // An older runner's status has no orphan list. Saying "none" would
            // be a claim this plugin cannot make from the data it has.
            tree.emptyText.text = "Update saffron-ai to the release with `saffron prune`"
            model.reload()
            return
        }
        count = orphans.size
        tree.emptyText.text = "Every recording belongs to a scenario"
        for ((kind, label) in listOf("cache" to "Caches", "proposal" to "Pending proposals", "artifact" to "Screenshot folders")) {
            val group = orphans.filter { it.kind == kind }
            if (group.isEmpty()) continue
            val parent = DefaultMutableTreeNode(OrphanRow(label, "${group.size}", AllIcons.Nodes.Folder))
            for (o in group) {
                parent.add(
                    DefaultMutableTreeNode(
                        if (kind == "artifact") {
                            // A folder of pictures: nothing to open as a file.
                            OrphanRow(
                                o.file.split('/').takeLast(2).joinToString("/"),
                                "failure screenshots of a scenario that is gone",
                                AllIcons.FileTypes.Image,
                                o.file,
                            )
                        } else {
                            OrphanRow(
                                o.scenario ?: o.file.substringAfterLast('/'),
                                "${o.feature ?: ""} · ${why(o.reason)}".trim(' ', '·'),
                                AllIcons.General.Warning,
                                "${o.file}\n${why(o.reason)}\nDouble-click to open the recording.",
                                o.file,
                            )
                        },
                    ),
                )
            }
            root.add(parent)
        }
        model.reload()
        TreeUtil.expandAll(tree)
    }

    private fun why(reason: String): String = when (reason) {
        "feature-gone" -> "the feature file is gone"
        "scenario-gone" -> "the scenario is gone from that file"
        else -> "it names no scenario"
    }

    private fun openSelected(): Boolean {
        val row = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? OrphanRow ?: return false
        val file = row.file ?: return false
        val base = project.basePath ?: return false
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/$file") ?: return false
        FileEditorManager.getInstance(project).openFile(vf, true)
        return true
    }

    /** Deleting recordings cannot be undone from here, so it is always asked. */
    private fun prune() {
        if (count == 0) {
            note.text = "Every recording belongs to a scenario. Nothing to prune."
            return
        }
        val answer = Messages.showYesNoDialog(
            project,
            "Delete $count item${if (count == 1) "" else "s"} that no scenario owns (recordings, proposals and screenshot folders)?\n\n" +
                "A recording has to be re-recorded if you delete it by mistake.",
            "Remove Orphaned Recordings",
            "Delete",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        SaffronRunner.execute(project, "Saffron: prune") {
            it.command = "prune"
            it.paths = ""
            it.extraArgs = "--yes"
        }
    }
}
