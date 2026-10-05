package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronRunner
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.MessageDialogBuilder
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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

/** `saffron runs --json`; [error] is the runner's reply when it failed some other way. */
class CiRuns(
    val provider: String? = null,
    val branch: String? = null,
    val runs: List<CiRun> = emptyList(),
    val problem: CiProblem? = null,
    val error: String? = null,
)

class CiArtifact(val name: String = "", val sizeBytes: Long = 0)
class CiBundle(val folder: String = "", val cached: Boolean = false, val artifacts: List<CiArtifact> = emptyList())
class CiTotals(val green: Int = 0, val yellow: Int = 0, val red: Int = 0, val aiCalls: Int? = null, val costUsd: Double? = null)
class CiScenario(
    val feature: String = "",
    val scenario: String = "",
    val displayName: String? = null,
    val status: String = "",
    val verified: Boolean? = null,
    val failingStep: String? = null,
    val error: String? = null,
    val narrative: String? = null,
    val trace: String? = null,
)
class CiViewProposal(val feature: String = "", val scenario: String = "", val verified: Boolean? = null, val importable: Boolean = false, val reason: String? = null)

/**
 * `saffron report --run <id> --json`: a CI run's results, viewed without importing.
 * [bundle] null with no [error]: the run has none, [reason] says why. [kind] "too-large"
 * with [sizeBytes]: above `--max-download`, ask and run again with a higher limit.
 */
class RunView(
    val error: String? = null,
    val kind: String? = null,
    val sizeBytes: Long? = null,
    val bundle: CiBundle? = null,
    val reasonKind: String? = null,
    val reason: String? = null,
    val totals: CiTotals? = null,
    val scenarios: List<CiScenario> = emptyList(),
    val proposals: List<CiViewProposal> = emptyList(),
    val warnings: List<String> = emptyList(),
)

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
 * JSON, the reason: a runner that predates the command says so, naming
 * [version] (the project's saffron-ai) when known. [run] starts the process
 * and waits for it.
 */
internal fun <T> saffronJson(
    base: String,
    type: Class<T>,
    vararg args: String,
    version: String? = null,
    run: (CapturingProcessHandler) -> ProcessOutput,
): Pair<T?, String?> {
    return try {
        val output = run(CapturingProcessHandler(SaffronCommand.base(base).withParameters(*args)).apply { setShouldDestroyProcessRecursively(true) })
        val parsed = output.stdout.takeIf { it.isNotBlank() }?.let { runCatching { Gson().fromJson(it, type) }.getOrNull() }
        if (parsed != null) return parsed to null
        val command = args.firstOrNull().orEmpty()
        val said = output.stderr.trim()
        null to when {
            output.isTimeout -> "saffron $command was stopped after 30 minutes. Run it in a terminal to see where it waits."
            Regex("unknown command '?$command").containsMatchIn(said) ->
                "CI runs need saffron-ai 0.9.3 or later" + (version?.takeIf { it.isNotBlank() }?.let { "; this project has $it." } ?: ".") +
                    " Update it: npm i -D saffron-ai@latest."
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
    /** A viewed run's scenario: Open Trace replays it when it has a trace. */
    val scenario: CiScenario? = null,
    /** A viewed run's results above the download limit, in bytes: double-click asks to download. */
    val tooLarge: Long? = null,
) {
    override fun toString(): String = label
}

/** Whole megabytes, rounded up: a limit of this many lets [bytes] through whether the runner counts MB or MiB. */
internal fun megabytes(bytes: Long): Long = ((bytes + 999_999) / 1_000_000).coerceAtLeast(1)

/** The download limit in MB from the settings; 0 is none. */
internal fun ciMaxDownloadMb(): Int = PropertiesComponent.getInstance().getInt(CI_MAX_DOWNLOAD_KEY, CI_MAX_DOWNLOAD_DEFAULT)

internal const val CI_MAX_DOWNLOAD_KEY = "ai.saffron.jetbrains.ciMaxDownloadMB"
internal const val CI_MAX_DOWNLOAD_DEFAULT = 100

private val NO_BUNDLE = mapOf("running" to "No results yet", "expired" to "Results expired", "none" to "No results uploaded")

/**
 * The rows under a viewed run: what `saffron report --run <id> --json` answered ([view]),
 * or why it did not ([problem]). [version] is the project's saffron-ai, [limitMb] the
 * download limit the answer was asked with. CI text is plain text; tooltips escape it.
 */
internal fun viewRows(view: RunView?, problem: String?, version: String? = null, limitMb: Int = 0): List<DefaultMutableTreeNode> {
    fun row(label: String, detail: String = "", icon: Icon? = null, tip: String? = null, scenario: CiScenario? = null, tooLarge: Long? = null) =
        DefaultMutableTreeNode(RunNode(label, detail, icon, tip, scenario = scenario, tooLarge = tooLarge))
    val error = view?.error ?: problem ?: if (view == null) "saffron report gave no answer" else null
    if (error != null) {
        if (error.contains("unknown option")) {
            val has = version?.takeIf { it.isNotBlank() }?.let { "this project has $it. " } ?: ""
            return listOf(row("Viewing results needs saffron-ai 0.9.7 or later", "${has}Update it: npm i -D saffron-ai@latest.", AllIcons.General.Warning, tooltipHtml(error)))
        }
        val size = view?.sizeBytes
        if (view?.kind == "too-large" && size != null) {
            val limit = if (limitMb > 0) "above the $limitMb MB download limit" else "above the download limit"
            return listOf(row("This run's results are ${megabytes(size)} MB", "$limit: double-click to download", AllIcons.Actions.Download, tooltipHtml(error), tooLarge = size))
        }
        return listOf(row("Results unavailable", error, AllIcons.General.Error, tooltipHtml(error)))
    }
    view!!
    if (view.bundle == null) {
        return listOf(row(NO_BUNDLE[view.reasonKind] ?: "No results", view.reason.orEmpty(), AllIcons.General.Information, tooltipHtml(view.reason)))
    }
    val rows = view.warnings.map { row(it, "", AllIcons.General.Warning, tooltipHtml(it)) }.toMutableList()
    view.totals?.let { t ->
        val cost = listOfNotNull(t.aiCalls?.let { "$it AI call${if (it == 1) "" else "s"}" }, t.costUsd?.let { "$" + "%.2f".format(it) }).joinToString(" · ")
        rows += row("${t.red} failed · ${t.yellow} pending review · ${t.green} passed", cost, AllIcons.General.Information)
    }
    for (status in listOf("red", "yellow")) {
        for (s in view.scenarios.filter { it.status == status }) {
            val firstError = s.error?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            // Yellow is healed, a first recording or a proposal not yet verified: all pending review.
            val found = listOfNotNull(s.failingStep, firstError).ifEmpty { listOf(if (status == "yellow") "pending review" else "failed") }
            val proof = when (s.verified) { true -> "verified"; false -> "unverified"; null -> null }
            val detail = (found + listOfNotNull(proof)).joinToString(" · ")
            rows += row(
                s.displayName ?: s.scenario,
                detail,
                if (status == "red") AllIcons.RunConfigurations.TestFailed else AllIcons.General.Warning,
                tooltipHtml("${s.feature} › ${s.scenario}", s.failingStep, firstError, s.narrative, if (s.trace != null) "Double-click or Open Trace replays it." else null),
                scenario = s,
            )
        }
    }
    if (view.proposals.isNotEmpty()) {
        val n = view.proposals.size
        val node = row("$n proposal${if (n == 1) "" else "s"}: ${view.proposals.count { it.importable }} importable", "Import Run files them here", AllIcons.Vcs.Patch)
        for (p in view.proposals) {
            val name = "${p.feature.substringAfterLast('/')} › ${p.scenario}"
            node.add(
                if (p.importable) row(name, "importable", AllIcons.Actions.Checked)
                else row(name, "not importable: ${p.reason ?: "no reason given"}", AllIcons.General.Error, tooltipHtml(p.reason)),
            )
        }
        rows += node
    }
    if (rows.isEmpty()) rows += row("No results in this run's bundle", "", AllIcons.General.Information)
    return rows
}

/**
 * `<command> --run <id>` with the provider; `--max-download` only for `report`,
 * which downloads: `saffron trace` in saffron-ai 0.9.7 rejects it, and replays what report kept.
 */
internal fun ciRunArgs(command: String, runId: String, provider: String?, limitMb: Long): List<String> =
    listOf(command, "--run", runId) +
        (provider?.let { listOf("--provider", it) } ?: emptyList()) +
        (if (command == "report" && limitMb > 0) listOf("--max-download", limitMb.toString()) else emptyList())

/** `feature:name` for `saffron trace`: the display name keeps an Examples row's "(example 1)" suffix. */
internal fun ciTraceSelector(s: CiScenario): String = "${s.feature}:${s.displayName ?: s.scenario}"

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
    /** Whether the tab is on screen; tests stand in for it. Results load only for a tab someone looks at. */
    private val visible: (() -> Boolean)? = null,
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
    /** Imports in flight, as "root|run id": a project switch does not forget them, only their own end does. */
    private val importing = mutableSetOf<String>()
    /** Viewed results per run id (`saffron report --run`), for the shown project: a switch clears them. */
    private val views = mutableMapOf<String, Pair<RunView?, String?>>()
    /** Views in flight, as "root|run id". */
    private val viewing = mutableSetOf<String>()
    /** Per run id: the download limit (MB) the user agreed to for a run above the setting's. */
    private val allowed = mutableMapOf<String, Long>()
    private var latest: ProjectStatus? = null
    /** The root the rows and notes belong to. */
    private var shownFor: Path? = null
    /** Per root: the number of its latest `saffron runs`; an older answer is dropped. */
    private val loads = mutableMapOf<Path, Int>()
    private var loadCount = 0
    /** Roots with `saffron runs` in flight, and those whose Refresh waits for it. */
    private val loading = mutableSetOf<Path>()
    private val again = mutableSetOf<Path>()
    /** CLI processes in flight: destroyed when the tab goes with its project. */
    private val processes = ConcurrentHashMap.newKeySet<CapturingProcessHandler>()
    @Volatile private var disposed = false
    /** The tab's own message (an import's summary): it outlasts status reloads until the selection changes. */
    private var ownNote: String? = null
    /** Set while [rebuild] replaces the rows: that selection change is not the user's. */
    private var rebuilding = false
    /**
     * The shown root's git HEAD file, where git says it is (a worktree's lives in the main
     * repository): a checkout of another branch changes it, and the runs listed are the branch's.
     */
    @Volatile private var head: String? = null
    private var headFor: Path? = null
    private var headWatch: LocalFileSystem.WatchRequest? = null

    init {
        toolbar = toolbar(
            action("Import Run", "saffron import --run: the selected run's proposals, checked against this checkout, and its report, screenshots and traces", AllIcons.Actions.Download) { importSelected() },
            action("Open Report", "saffron report --run: the selected run's HTML report in your browser, without importing", AllIcons.Actions.Preview) { openReport() },
            action("Open Trace", "saffron trace --run: replay the selected scenario of the run, without importing", AllIcons.Actions.Execute) { openTrace() },
            action("Open in Browser", "The selected run on GitHub or Azure DevOps", AllIcons.General.Web) { openSelected() },
            action("Refresh", "List the runs again (saffron runs) and load the selected run's results again", AllIcons.Actions.Refresh) {
                views.clear()
                loadRuns()
            },
        )
        tree.emptyText.text = "Loading…"
        tree.addTreeSelectionListener {
            if (!rebuilding) {
                ownNote = null
                clearNote()
                viewSelected()
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val r = (tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? RunNode ?: return
                when {
                    r.tooLarge != null -> selectedRun()?.let { askDownload(it, r.tooLarge) }
                    r.scenario?.trace != null -> openTrace()
                }
            }
        })
        // Shown again: the selected run's results, if they were waiting for it.
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && showing()) viewSelected()
        }
        // Run names and errors come from CI: never rendered as HTML.
        note.putClientProperty("html.disable", true)
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        })
        Disposer.register(parent) {
            disposed = true
            processes.forEach { it.destroyProcess() }
            headWatch?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
        }
        // Another branch checked out: its runs, not the last branch's.
        project.messageBus.connect(parent).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val watched = head ?: return
                if (events.any { it.path == watched }) ApplicationManager.getApplication().invokeLater({ loadRuns() }, project.disposed)
            }
        })
        // Its first render lists the runs.
        start()
    }

    override fun render(status: ProjectStatus?) {
        latest = status
        ownNote?.let { note.text = it }
        // Not listed yet (trust granted since), or another project chosen: its runs, never the last project's.
        if (listedFor != projectRoot) loadRuns(refresh = false) else rebuild()
    }

    /** Runs [wait] on a process the tab can destroy on disposal; forgets it once done. */
    private fun tracked(wait: (CapturingProcessHandler) -> ProcessOutput): (CapturingProcessHandler) -> ProcessOutput = { handler ->
        processes += handler
        try {
            if (disposed) handler.destroyProcess()
            wait(handler)
        } finally {
            processes -= handler
        }
    }

    private fun say(text: String) {
        ownNote = text
        note.text = text
    }

    /** `saffron runs` for the shown root. [refresh]: the user asked, so a list already in flight is followed by one more. */
    private fun loadRuns(refresh: Boolean = true) {
        if (!trusted()) {
            tree.emptyText.text = SaffronStatusService.UNTRUSTED
            return
        }
        val base = projectRoot
        watchHead(base)
        if (shownFor != base) {
            // Another project, or trust granted: nothing of before stays, to be imported or read here.
            shownFor = base
            listed = null
            listedFor = null
            imports.clear()
            views.clear()
            allowed.clear()
            ownNote = null
            clearNote()
            root.removeAllChildren()
            model.reload()
        }
        tree.emptyText.text = "Loading…"
        if (base in loading) {
            if (refresh) again.add(base)
            return
        }
        loading.add(base)
        val load = ++loadCount
        loads[base] = load
        val version = latest?.version
        ApplicationManager.getApplication().executeOnPooledThread {
            // The runner gives each gh or az call 10 minutes and may make several: 30 minutes stops only one that hangs.
            val (runs, problem) = saffronJson(base.toString(), CiRuns::class.java, "runs", "--json", version = version, run = tracked { it.runProcess(30 * 60_000, true) })
            ApplicationManager.getApplication().invokeLater({
                loading.remove(base)
                // Not the latest list of this root, or listed for a project no longer shown: dropped.
                if (loads[base] != load) return@invokeLater
                if (base != projectRoot) {
                    again.remove(base)
                    return@invokeLater
                }
                // A Refresh came meanwhile: its answer, not this one.
                if (again.remove(base)) {
                    loadRuns()
                    return@invokeLater
                }
                // A runner's {error} is shown as the reason, never as "No CI runs".
                listed = runs?.takeIf { it.error == null }
                    ?: CiRuns(problem = CiProblem("failed", runs?.error ?: problem ?: "saffron runs gave no answer"))
                listedFor = base
                rebuild()
                viewSelected()
            }, project.disposed)
        }
    }

    /** Finds [base]'s HEAD file off the EDT and watches it; a root shown since makes its own. */
    private fun watchHead(base: Path) {
        if (headFor == base) return
        headFor = base
        head = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val said = runCatching { ExecUtil.execAndGetOutput(GeneralCommandLine("git", "rev-parse", "--git-path", "HEAD").withWorkDirectory(base.toFile()), 10_000) }.getOrNull()
            val file = said?.takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf { it.isNotEmpty() }?.let { base.resolve(it).normalize() } ?: return@executeOnPooledThread
            val fs = LocalFileSystem.getInstance()
            val request = fs.addRootToWatch(file.parent.toString(), false)
            // Loaded into the VFS, or no change to it is ever reported.
            fs.refreshAndFindFileByNioFile(file)
            ApplicationManager.getApplication().invokeLater({
                if (headFor != base || disposed) {
                    request?.let { fs.removeWatchedRoot(it) }
                    return@invokeLater
                }
                headWatch?.let { fs.removeWatchedRoot(it) }
                headWatch = request
                head = FileUtil.toSystemIndependentName(file.toString())
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
            when {
                "$projectRoot|${run.id}" in viewing -> node.add(DefaultMutableTreeNode(RunNode("Loading results…", "saffron report --run ${run.id}", AllIcons.Process.Step_1)))
                else -> views[run.id]?.let { (view, problem) -> viewRows(view, problem, latest?.version, (allowed[run.id] ?: ciMaxDownloadMb().toLong()).toInt()).forEach(node::add) }
            }
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

    private fun showing(): Boolean = visible?.invoke() ?: isShowing

    /** `report --run <id>` with the provider and the download limit, as [run]'s view and actions use it. */
    private fun reportArgs(command: String, run: CiRun): List<String> =
        ciRunArgs(command, run.id, listed?.provider, allowed[run.id] ?: ciMaxDownloadMb().toLong())

    /** The selected run's results, once per run and project, and only while the tab is on screen. */
    private fun viewSelected() {
        if (!showing() || !trusted()) return
        val run = selectedRun() ?: return
        val base = listedFor ?: return
        if (base != projectRoot || run.id in views) return
        view(base, run)
    }

    /** `saffron report --run <id> --json` as a background task Cancel stops, like Import Run. */
    private fun view(base: Path, run: CiRun) {
        val key = "$base|${run.id}"
        if (!viewing.add(key)) return
        val version = latest?.version
        val args = reportArgs("report", run) + "--json"
        rebuild()
        expand(run)
        object : Task.Backgroundable(project, "Loading the results of ${run.name} #${run.number}", true) {
            private var answer: Pair<RunView?, String?> = null to null

            override fun run(indicator: ProgressIndicator) {
                answer = saffronJson(base.toString(), RunView::class.java, *args.toTypedArray(), version = version, run = tracked { it.runProcessWithProgressIndicator(indicator) })
            }

            override fun onCancel() {
                viewing -= key
                if (base == projectRoot) {
                    say("Loading the results of ${run.name} #${run.number} was cancelled. Select the run again to load them.")
                    rebuild()
                }
            }

            override fun onFinished() {
                if (viewing.remove(key) && base == projectRoot) rebuild()
            }

            override fun onSuccess() {
                viewing -= key
                // Loaded for a project no longer shown: dropped, the shown one has its own.
                if (base != projectRoot || listedFor != base) return
                views[run.id] = answer
                rebuild()
                expand(run)
                val (view, _) = answer
                if (view?.kind == "too-large" && view.sizeBytes != null && showing() && selectedRun()?.id == run.id) askDownload(run, view.sizeBytes)
            }
        }.queue()
    }

    /** Above the download limit: asks, and loads again with a limit above the run's size. */
    private fun askDownload(run: CiRun, sizeBytes: Long) {
        val base = listedFor ?: return
        if (base != projectRoot) return
        val mb = megabytes(sizeBytes)
        if (!MessageDialogBuilder.yesNo("Download CI Results", "This run's results are $mb MB. Download?").yesText("Download").noText("Not Now").ask(project)) return
        allowed[run.id] = mb + 1
        views.remove(run.id)
        view(base, run)
    }

    private fun expand(run: CiRun) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            if ((child.userObject as? RunNode)?.run?.id == run.id) tree.expandPath(TreePath(child.path))
        }
    }

    /** The selected run, when it is the shown project's; else says to select one. */
    private fun runHere(): CiRun? {
        val run = selectedRun()
        if (run == null || listedFor == null || listedFor != projectRoot) {
            say("Select a run first.")
            return null
        }
        return run
    }

    /** `saffron report --run <id>`: renders the run's report and opens it in the browser. Files nothing. */
    private fun openReport() {
        val run = runHere() ?: return
        SaffronRunner.execute(project, "Saffron: CI report") {
            it.command = "report"
            it.extraArgs = reportArgs("report", run).drop(1).joinToString(" ")
        }
    }

    /** `saffron trace --run <id> <feature:scenario>`: the selected scenario's replay, view-only. */
    private fun openTrace() {
        val run = runHere() ?: return
        val s = ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? RunNode)?.scenario
        if (s?.trace == null) {
            say(if (s == null) "Select a failed or pending review scenario under the run." else "No trace for \"${s.scenario}\" in this run.")
            return
        }
        SaffronRunner.execute(project, "Saffron: CI replay") {
            it.command = "trace"
            it.paths = ciTraceSelector(s)
            it.extraArgs = reportArgs("trace", run).drop(1).joinToString(" ")
        }
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
        // The run is the listed project's: never imported into one chosen since.
        val base = listedFor
        if (base == null || base != projectRoot) {
            say("Select a run to import.")
            return
        }
        val key = "$base|${run.id}"
        if (key in importing) return
        importing += key
        val version = latest?.version
        say("Importing ${run.name} #${run.number}…")
        // No time limit: a slow download that would finish is not killed. Cancel in the progress bar stops it.
        object : Task.Backgroundable(project, "Importing ${run.name} #${run.number}", true) {
            private var answer: Pair<RunImport?, String?> = null to null

            override fun run(indicator: ProgressIndicator) {
                answer = saffronJson(base.toString(), RunImport::class.java, "import", "--run", run.id, "--json", version = version, run = tracked { it.runProcessWithProgressIndicator(indicator) })
            }

            override fun onCancel() {
                // The runner writes as it goes: stopped part way, some of the run may be here already.
                if (base == projectRoot) {
                    say("The import was cancelled part way: what it had written by then (the run's report, screenshots, traces or proposals) stays in this checkout. Import the run again to finish it.")
                    refreshStatus()
                }
            }

            override fun onFinished() {
                importing -= key
            }

            override fun onSuccess() {
                // Imported into a project no longer shown: its rows are not these.
                if (base != projectRoot) return
                val (result, problem) = answer
                val outcome = result ?: RunImport(error = problem)
                imports[run.id] = outcome
                say(summary(outcome, run))
                rebuild()
                expand(run)
                // The proposals, the report and the evidence changed on disk.
                refreshStatus()
            }
        }.queue()
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
