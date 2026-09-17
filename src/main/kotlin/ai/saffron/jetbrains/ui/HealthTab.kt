package ai.saffron.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** One row of the Health tree: label, grey detail, icon. */
private class Row(val label: String, val detail: String = "", val icon: Icon? = null, val tooltip: String? = null) {
    override fun toString(): String = label
}

/** Vocabulary health and the effective configuration, as a tree. */
class HealthTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val root = DefaultMutableTreeNode(Row("Saffron"))
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val r = (value as? DefaultMutableTreeNode)?.userObject as? Row ?: return
                icon = r.icon
                append(r.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (r.detail.isNotEmpty()) append("  ${r.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = r.tooltip
            }
        }
    }

    init {
        toolbar = toolbar(
            action("Open saffron.config.json", "Edit the configuration file", AllIcons.Actions.Edit) { openConfig() },
            action("Expand All", "Expand every section", AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
        tree.emptyText.text = "Loading…"
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        root.removeAllChildren()
        if (status == null) {
            model.reload()
            return
        }
        val v = status.vocabulary

        val vocab = node(Row("Vocabulary", "${v.steps} steps · ${v.recorded} recorded · ${v.unrecorded} unrecorded · ${v.stepSets} step sets", AllIcons.Nodes.Folder))
        root.add(vocab)

        val divergent = node(
            Row(
                "Divergent steps",
                if (v.divergent.isEmpty()) "none" else "${v.divergent.size} · same text, different recordings",
                if (v.divergent.isEmpty()) AllIcons.General.InspectionsOK else AllIcons.General.Warning,
                "A step recorded differently in two caches. Replay uses the newest; re-record or accept a rename edit to converge.",
            ),
        )
        for (d in v.divergent) divergent.add(node(Row(d, "", AllIcons.Nodes.Variable)))
        root.add(divergent)

        val dupes = node(
            Row(
                "Duplicate wordings",
                if (v.duplicateWordings.isEmpty()) "none" else "${v.duplicateWordings.size} group${if (v.duplicateWordings.size == 1) "" else "s"} · same recording, different words",
                if (v.duplicateWordings.isEmpty()) AllIcons.General.InspectionsOK else AllIcons.General.Warning,
                "Different wordings whose recorded actions are identical: each extra wording was a recording paid twice. Rename to one wording.",
            ),
        )
        for (g in v.duplicateWordings) {
            val group = node(Row(g.steps.first(), "+ ${g.steps.size - 1} more · ${g.actions}", AllIcons.Actions.Diff, g.actions))
            for (t in g.steps) group.add(node(Row(t, "", AllIcons.Nodes.Variable)))
            dupes.add(group)
        }
        root.add(dupes)

        val config = node(Row("Config", status.config.file ?: "defaults, no saffron.config.json", AllIcons.General.Settings, "Double-click a value's file with Open saffron.config.json"))
        for ((k, value) in status.config.effective.entrySet()) {
            config.add(node(Row(k, if (value.isJsonPrimitive) value.asString else value.toString(), AllIcons.Nodes.Property)))
        }
        root.add(config)

        root.add(node(Row("Runner", "saffron-ai ${status.version}${if (status.packageInstalled) " · project install" else " · npx"}", AllIcons.Nodes.PpLib)))

        if (status.history.size > 1) {
            val recent = status.history.takeLast(10)
            val rates = recent.map { r -> val n = r.green + r.yellow + r.red; if (n == 0) 0 else (r.green + r.yellow) * 100 / n }
            root.add(node(Row("Pass rate, last ${recent.size} runs", rates.joinToString(" · ") { "$it%" }, AllIcons.Actions.Profile, "See the Dashboard tab for the full trend.")))
        }

        model.reload()
        TreeUtil.expand(tree, 1)
    }

    private fun node(row: Row): DefaultMutableTreeNode = DefaultMutableTreeNode(row)

    private fun openConfig() {
        val base = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/saffron.config.json")
        if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true) else note.text = "No saffron.config.json yet: run npx saffron init."
    }
}
