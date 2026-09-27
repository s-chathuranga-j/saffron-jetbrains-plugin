package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
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

/** What opening a row's screenshot comes to: the file to show, or why there is none. */
internal class Screenshot(val file: VirtualFile? = null, val problem: String? = null)

/**
 * Reads, checks and copies a row's picture, then finds the copy in the VFS.
 * Disk work that takes long enough on a WSL path or a network share, with a
 * full-page picture, to freeze the IDE, so it is never run on the UI thread.
 */
internal fun screenshotToShow(base: String, shot: StatusEvidence, scenario: String): Screenshot {
    val file = java.io.File(base, shot.file)
    if (!file.isFile) return Screenshot(problem = "The screenshot of \"$scenario\" is gone. Screenshots are kept for the latest run only.")
    val bytes = currentEvidence(file, shot.sha256)
        ?: return Screenshot(problem = "A later run replaced the screenshot of \"$scenario\". Refresh to see its results.")
    // The checked bytes, not the path: a run writing the file between the
    // check and the editor's own read would otherwise still be shown. A
    // runner before digests has nothing to check against, so the path opens
    // as it is.
    val shown = shot.sha256?.let { sha ->
        try {
            checkedCopy(bytes, sha, file)
        } catch (e: java.io.IOException) {
            return Screenshot(problem = "Could not prepare the screenshot of \"$scenario\" for viewing: ${e.message}")
        }
    } ?: file
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(shown) ?: return Screenshot()
    // The editor reads the IDE's copy of the file, which can still hold
    // an earlier picture at this path. Bring it up to date.
    VfsUtil.markDirtyAndRefresh(false, false, false, vf)
    return Screenshot(file = vf)
}

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
    /** When the listed run started: Open Replay names it, so a later run is not opened in its place. */
    private var shownRun: String? = null
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
            action("Open Replay", "Step through the selected scenario's run: the page at every action, the network alongside (saffron trace)", AllIcons.Actions.Execute) { openReplay() },
            action("Open Scenario", "Jump to the selected scenario in its feature file", AllIcons.Actions.EditSource) { openScenario() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
        tree.emptyText.text = "Loading…"
        object : DoubleClickListener() {
            // The scenario only when there is no picture: a picture that is
            // gone or replaced says so on the note, and jumping away would hide it.
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val a = selected() ?: return false
                return if (a.evidence.any { it.kind != "trace" }) openScreenshot() else openScenario()
            }
        }.installOn(tree)
        // A message about one row must not sit above another.
        tree.addTreeSelectionListener { clearNote() }
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
        // Runners before 0.9.1 have no --run and would refuse the command itself.
        shownRun = run?.startedAt?.takeIf { it.isNotBlank() && runnerAtLeast(status?.version ?: "", 0, 9, 1) }
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
                val shot = a.evidence.firstOrNull { it.kind != "trace" }
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

    /** `saffron trace <scenario>` in the Run tool window; it prints the replay's URL and opens it. */
    private fun openReplay(): Boolean {
        val a = selected() ?: return false
        if (a.evidence.none { it.kind == "trace" }) {
            // An older runner has no trace setting, so following the second
            // half alone would bring this same message back.
            note.text = "No execution trace for \"${a.scenario}\". Open Replay needs saffron-ai 0.9.0 or later, with \"trace\": \"retain-on-failure\" in saffron.config.json; then run again."
            return false
        }
        clearNote()
        SaffronRunner.execute(project, "Saffron: replay") {
            it.command = "trace"
            // feature:scenario, so two features with a scenario of the same
            // name are told apart. Written as it is: it starts with the
            // feature path, never a quote, so the field takes it whole,
            // quotes and spacing in the name included.
            it.paths = "${a.feature}:${a.scenario}"
            // The run these rows list: the replay refuses a later one.
            it.extraArgs = shownRun?.let { run -> "--run $run" } ?: ""
        }
        return true
    }

    private fun openScreenshot(): Boolean {
        val a = selected() ?: return false
        // A trace is not a picture; the pictures come first, the replay has its own action.
        val shot = a.evidence.firstOrNull { it.kind != "trace" } ?: return false
        val base = project.basePath ?: return false
        clearNote()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val result = screenshotToShow(base, shot, a.scenario)
            app.invokeLater({
                // Names its scenario: the selection may have moved on meanwhile.
                result.problem?.let { note.text = it }
                result.file?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            }, project.disposed)
        }
        return true
    }

    private fun openScenario(): Boolean {
        val a = selected() ?: return false
        val base = project.basePath ?: return false
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/${a.feature}") ?: return false
        val line = features.firstOrNull { it.path == a.feature }?.scenarios?.firstOrNull { it.name == a.baseScenario }?.line
        OpenFileDescriptor(project, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
        clearNote()
        return true
    }
}
