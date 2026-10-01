package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import com.google.gson.Gson
import com.intellij.execution.util.ExecUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** One run of `saffron runs --json`: the same fields whichever service ran it. */
class CiRun(
    val id: String = "",
    val name: String = "",
    val number: String = "",
    val title: String? = null,
    val status: String = "queued",
    val conclusion: String? = null,
    val event: String? = null,
    val branch: String? = null,
    val commit: String? = null,
    val createdAt: String? = null,
    val url: String? = null,
    val attempt: Int? = null,
    /** Proposals pending here that came from this run. */
    val pending: Int = 0,
)

class CiProblem(val kind: String = "failed", val message: String = "")

/** `saffron runs --json`. */
class CiRuns(val provider: String? = null, val branch: String? = null, val runs: List<CiRun> = emptyList(), val problem: CiProblem? = null)

class ImportedOutcome(val file: String = "", val feature: String = "", val scenario: String = "", val outcome: String = "", val reason: String? = null)

/** `saffron import --run <id> --json`: what happened to each proposal, or why nothing was imported. */
class RunImport(
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val proposals: List<ImportedOutcome> = emptyList(),
    val report: String? = null,
)

/**
 * `saffron <args>` in a project, its output read as JSON whatever the exit
 * code: the runner prints its JSON for a refusal or a problem too. Without
 * JSON, the reason: a runner that predates the command says so.
 */
internal fun <T> saffronJson(base: String, type: Class<T>, vararg args: String): Pair<T?, String?> {
    return try {
        val output = ExecUtil.execAndGetOutput(SaffronCommand.base(base).withParameters(*args), 600_000)
        val parsed = output.stdout.takeIf { it.isNotBlank() }?.let { runCatching { Gson().fromJson(it, type) }.getOrNull() }
        if (parsed != null) return parsed to null
        val command = args.firstOrNull().orEmpty()
        val said = output.stderr.trim()
        null to when {
            Regex("unknown command '?$command").containsMatchIn(said) -> "This saffron-ai has no saffron $command: update it (npm i -D saffron-ai@latest)."
            said.isNotEmpty() -> runnerError(said)
            else -> "saffron $command exited with ${output.exitCode}"
        }
    } catch (e: Exception) {
        null to (e.message ?: e.toString())
    }
}

private val PROVIDERS = mapOf("github" to "GitHub Actions", "azure" to "Azure Pipelines")
private val PROBLEMS = mapOf("missing-tool" to "CI tools not set up", "signed-out" to "Not signed in", "not-a-repository" to "Repository not recognized")

internal class RunNode(
    val label: String,
    val detail: String = "",
    val icon: Icon? = null,
    val tooltip: String? = null,
    val run: CiRun? = null,
) {
    override fun toString(): String = label
}

/** CI-controlled text as a Swing tooltip: control characters out, markup escaped, one line each. */
internal fun tooltipHtml(vararg lines: String?): String? {
    val shown = lines.filterNotNull().map { StringUtil.escapeXmlEntities(it.replace(Regex("\\p{Cntrl}"), " ")) }
    return if (shown.isEmpty()) null else shown.joinToString("<br>", "<html>", "</html>")
}

/** "3 h ago" for a row. */
internal fun ago(iso: String?, now: Instant = Instant.now()): String? {
    val then = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val minutes = Duration.between(then, now).toMinutes()
    if (minutes < 0) return null
    if (minutes < 60) return "$minutes min ago"
    val hours = (minutes + 30) / 60
    return if (hours < 48) "$hours h ago" else "${(hours + 12) / 24} days ago"
}

/**
 * CI runs: a heal made in CI, reviewed here. The runner does the CI work
 * behind one contract shared with the VS Code extension: `saffron runs
 * --json` lists the branch's runs (GitHub Actions through gh, Azure
 * Pipelines through az) and `saffron import --run <id> --json` downloads one
 * run's bundle and imports it, checked against this checkout. gh and az hold
 * the credentials. After Accept, the new recordings are a change to commit
 * with the IDE's own version control.
 */
class CiRunsTab(
    project: Project,
    parent: Disposable,
    private val trusted: () -> Boolean = { SaffronCommand.trusted(project) },
) : StatusTab(project, parent) {

    private val root = DefaultMutableTreeNode(RunNode("CI runs"))
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val r = (value as? DefaultMutableTreeNode)?.userObject as? RunNode ?: return
                icon = r.icon
                append(r.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (r.detail.isNotEmpty()) append("  ${r.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = r.tooltip
            }
        }
    }

    /** The runs as last listed, and the project they belong to. Touched on the EDT only. */
    private var listed: CiRuns? = null
    private var listedFor: Path? = null
    private val imports = mutableMapOf<String, RunImport>()
    private val importing = mutableSetOf<String>()
    private var latest: ProjectStatus? = null
    /** The root whose runs are being listed. */
    private var loadingFor: Path? = null
    /** The tab's own message (an import's summary): it outlasts status reloads until the selection changes. */
    private var ownNote: String? = null
    /** Set while [rebuild] replaces the rows: that selection change is not the user's. */
    private var rebuilding = false

    init {
        toolbar = toolbar(
            action("Import Run", "saffron import --run: the selected run's proposals, checked against this checkout, and its report, screenshots and traces", AllIcons.Actions.Download) { importSelected() },
            action("Open in Browser", "The selected run on GitHub or Azure DevOps", AllIcons.General.Web) { openSelected() },
            action("Refresh", "List the runs again (saffron runs)", AllIcons.Actions.Refresh) { loadRuns() },
        )
        tree.emptyText.text = "Loading…"
        tree.addTreeSelectionListener {
            if (!rebuilding) {
                ownNote = null
                clearNote()
            }
        }
        // Run names and errors come from CI: never rendered as HTML.
        note.putClientProperty("html.disable", true)
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        })
        // Its first render lists the runs.
        start()
    }

    override fun render(status: ProjectStatus?) {
        latest = status
        ownNote?.let { note.text = it }
        // Not listed yet (trust granted since), or another project chosen: its runs, never the last project's.
        if (listedFor != projectRoot && loadingFor != projectRoot) loadRuns() else rebuild()
    }

    private fun say(text: String) {
        ownNote = text
        note.text = text
    }

    private fun loadRuns() {
        if (!trusted()) {
            tree.emptyText.text = SaffronStatusService.UNTRUSTED
            return
        }
        val base = projectRoot
        if (listedFor != base) {
            // Another project: nothing of the last one's stays, to be imported here.
            listed = null
            listedFor = null
            imports.clear()
            importing.clear()
            ownNote = null
            root.removeAllChildren()
            model.reload()
        }
        loadingFor = base
        tree.emptyText.text = "Loading…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val (runs, problem) = saffronJson(base.toString(), CiRuns::class.java, "runs", "--json")
            ApplicationManager.getApplication().invokeLater({
                if (loadingFor == base) loadingFor = null
                // Listed for a project no longer shown: dropped.
                if (base != projectRoot) return@invokeLater
                listed = runs ?: CiRuns(problem = CiProblem("failed", problem ?: "saffron runs gave no answer"))
                listedFor = base
                rebuild()
            }, project.disposed)
        }
    }

    /** Pending proposals per run: from the status once it has reloaded after an import, else as listed. */
    private fun pending(provider: String?, run: CiRun): Int =
        latest?.proposals?.count { it.importedFrom?.run == "$provider:${run.id}" } ?: run.pending

    private fun rebuild() {
        val runs = listed ?: return
        val selected = selectedRun()?.id
        // Plain JTree calls: which runs were open, so a reload keeps them open.
        val expanded = (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }
            .filter { tree.isExpanded(TreePath(it.path)) }
            .mapNotNull { (it.userObject as? RunNode)?.run?.id }
            .toSet()
        root.removeAllChildren()
        val problem = runs.problem
        tree.emptyText.text = when {
            problem != null -> ""
            runs.branch != null -> "No ${PROVIDERS[runs.provider] ?: "CI"} runs on ${runs.branch}"
            else -> "No ${PROVIDERS[runs.provider] ?: "CI"} runs"
        }
        if (problem != null) {
            root.add(DefaultMutableTreeNode(RunNode(PROBLEMS[problem.kind] ?: "CI runs unavailable", problem.message, AllIcons.General.Warning, tooltipHtml(problem.message))))
        }
        for (run in runs.runs) {
            val outcome = if (run.status == "completed") run.conclusion ?: "completed" else run.status.replace('_', ' ')
            val waiting = pending(runs.provider, run)
            val icon = when (outcome) {
                "success" -> AllIcons.RunConfigurations.TestPassed
                "failure" -> AllIcons.RunConfigurations.TestFailed
                "cancelled" -> AllIcons.RunConfigurations.TestTerminated
                "partial" -> AllIcons.General.Warning
                "in progress", "queued" -> AllIcons.RunConfigurations.TestNotRan
                else -> AllIcons.RunConfigurations.TestIgnored
            }
            val node = DefaultMutableTreeNode(
                RunNode(
                    "${run.name} #${run.number}",
                    listOfNotNull(outcome, ago(run.createdAt), if (waiting > 0) "$waiting proposal${if (waiting == 1) "" else "s"} pending here" else null).joinToString(" · "),
                    icon,
                    tooltipHtml(
                        run.title,
                        "${run.name} #${run.number}" + (run.attempt?.takeIf { it > 1 }?.let { ", attempt $it" } ?: "") + " · $outcome" + (run.event?.let { " · $it" } ?: ""),
                        if (run.branch != null || run.commit != null) "${run.branch ?: "detached"}" + (run.commit?.let { " @ ${it.take(7)}" } ?: "") else null,
                        "Import Run brings its Saffron bundle into this checkout: the proposals it filed, checked against your files, and its report, screenshots and traces.",
                    ),
                    run,
                ),
            )
            imports[run.id]?.let { result -> outcomeRows(result).forEach(node::add) }
            root.add(node)
        }
        // The reload drops the selection: put the run back, the note with it.
        rebuilding = true
        try {
            model.reload()
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) as DefaultMutableTreeNode
                val id = (child.userObject as? RunNode)?.run?.id ?: continue
                if (id in expanded) tree.expandPath(TreePath(child.path))
                if (id == selected) tree.selectionPath = TreePath(child.path)
            }
        } finally {
            rebuilding = false
        }
    }

    /** What importing a run did: warnings first, then each proposal. */
    private fun outcomeRows(result: RunImport): List<DefaultMutableTreeNode> {
        result.error?.let { return listOf(DefaultMutableTreeNode(RunNode(it, "", AllIcons.General.Error, tooltipHtml(it)))) }
        val rows = result.warnings.map { DefaultMutableTreeNode(RunNode(it, "", AllIcons.General.Warning, tooltipHtml(it))) }.toMutableList()
        for (p in result.proposals) {
            val name = if (p.feature.isNotEmpty()) "${p.feature.substringAfterLast('/')} › ${p.scenario}" else p.file
            rows += when (p.outcome) {
                "imported" -> DefaultMutableTreeNode(RunNode(name, "imported: review it in Proposals", AllIcons.Actions.Checked))
                "unchanged" -> DefaultMutableTreeNode(RunNode(name, p.reason ?: "already pending here", AllIcons.General.Information))
                else -> DefaultMutableTreeNode(RunNode(name, "not imported: ${p.reason}", AllIcons.General.Error, tooltipHtml(p.reason)))
            }
        }
        if (result.proposals.isEmpty()) {
            rows += DefaultMutableTreeNode(RunNode("No proposals in this run", if (result.report != null) "its report, screenshots and traces are in Last Run" else "", AllIcons.General.Information))
        }
        return rows
    }

    private fun selectedRun(): CiRun? {
        var node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        // A child row stands for its run.
        while (node != null && (node.userObject as? RunNode)?.run == null) node = node.parent as? DefaultMutableTreeNode
        return (node?.userObject as? RunNode)?.run
    }

    private fun openSelected() {
        val url = selectedRun()?.url?.takeIf { it.startsWith("https://") } ?: return
        BrowserUtil.browse(url)
    }

    private fun importSelected() {
        if (!trusted()) {
            say(SaffronStatusService.UNTRUSTED)
            return
        }
        val run = selectedRun()
        if (run == null) {
            say("Select a run to import.")
            return
        }
        if (run.id in importing) return
        importing += run.id
        say("Importing ${run.name} #${run.number}…")
        val base = projectRoot
        ApplicationManager.getApplication().executeOnPooledThread {
            val (result, problem) = saffronJson(base.toString(), RunImport::class.java, "import", "--run", run.id, "--json")
            ApplicationManager.getApplication().invokeLater({
                importing -= run.id
                // Imported into a project no longer shown: its rows are not these.
                if (base != projectRoot) return@invokeLater
                val outcome = result ?: RunImport(error = problem)
                imports[run.id] = outcome
                say(summary(outcome, run))
                rebuild()
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) as DefaultMutableTreeNode
                    if ((child.userObject as? RunNode)?.run?.id == run.id) tree.expandPath(TreePath(child.path))
                }
                // The proposals, the report and the evidence changed on disk.
                refreshStatus()
            }, project.disposed)
        }
    }

    private fun summary(result: RunImport, run: CiRun): String {
        result.error?.let { return "${run.name} #${run.number} was not imported: $it" }
        val imported = result.proposals.count { it.outcome == "imported" }
        val refused = result.proposals.count { it.outcome == "refused" }
        if (imported == 0 && refused == 0 && result.proposals.isNotEmpty()) return "Already imported: nothing changed for ${run.name} #${run.number}."
        return listOfNotNull(
            "$imported proposal${if (imported == 1) "" else "s"} from ${run.name} #${run.number} ready for review in Proposals",
            if (refused > 0) "$refused not imported" else null,
            if (result.warnings.isNotEmpty()) "${result.warnings.size} warning${if (result.warnings.size == 1) "" else "s"}" else null,
        ).joinToString(", ") + "."
    }
}
