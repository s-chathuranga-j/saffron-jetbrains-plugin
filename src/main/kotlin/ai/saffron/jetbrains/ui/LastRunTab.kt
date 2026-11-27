package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronRunner
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
import javax.swing.tree.TreePath

/** What opening a row's screenshot comes to: the file to show, or why there is none. */
internal class Screenshot(val file: VirtualFile? = null, val problem: String? = null)

/** What the tab says when it lists no scenario. An interrupted run never reads as everything passed. */
internal fun lastRunEmptyText(status: ProjectStatus?, teardownFailed: Boolean = false, thisRun: ThisRun? = status?.lastRun?.let(::thisRun)): String {
    val run = status?.lastRun
    return when {
        status == null -> "Loading…"
        run == null -> "No run yet"
        // Saying "everything passed" would be a claim this data cannot support.
        run.attention == null -> "Update saffron-ai to 0.7.2 or later, which lists failure screenshots"
        run.interrupted -> "Run interrupted: ${run.notRun ?: "some"} scenario(s) did not run, and count as neither passed nor failed"
        // The runner exits 2 for it (VS Code's words).
        teardownFailed -> "Teardown failed: the scenarios passed; check for processes or containers left running"
        // A run of named scenarios folded into the earlier report: its own counts, the kept rows apart.
        thisRun != null -> "Everything passed: ${thisRun.counts} in this run, ${thisRun.earlierRows} more in the report from earlier runs"
        else -> "Everything passed: ${run.totals.green} green in the last run"
    }
}

/** A run of named scenarios folded into the earlier report (the runner's foldIntoRun): its own [counts], and how many rows came from earlier runs. */
internal class ThisRun(val counts: String, val earlierRows: Int)

/** From saffron-ai's `lastRun.counts` and `combined`; null from a runner without them (the report says it then) or with no earlier rows. */
internal fun thisRun(run: StatusLastRun): ThisRun? = run.combined?.takeIf { it.earlierRows > 0 && run.counts != null }?.let { ThisRun(run.counts!!, it.earlierRows) }

/** A browser matrix lists a scenario once per browser: the label says which. */
internal fun attentionLabel(a: StatusAttention, matrix: Boolean): String =
    if (matrix && a.browser != null) "${a.scenario} · ${a.browser}" else a.scenario

/** A red the app did not cause (`blocked`, saffron-ai 1.4.0) is not run, with the runner's reason, as its status counts it. */
internal fun attentionNotRun(a: StatusAttention): Boolean = a.status == "red" && a.blocked != null

/** A row's grey text: the failed step or the feature, after "not run · <reason>" for a red that did not run. */
internal fun attentionDetail(a: StatusAttention): String =
    (if (attentionNotRun(a)) listOfNotNull("not run", BLOCKED_LABEL[a.blocked]) else emptyList()).plus(a.failedStep ?: a.feature).joinToString(" · ")

/**
 * `feature:scenario` for `saffron trace`, so two features with a scenario of the same name
 * are told apart, and `@browser` when the row names one (saffron-ai 0.9.9 and later), so a
 * browser matrix opens that browser's execution alone.
 */
internal fun attentionTraceSelector(a: StatusAttention): String =
    "${a.feature}:${a.scenario}" + (a.browser?.let { "@$it" } ?: "")

/**
 * The picture a row opens, as the runner picks it (src/cli/ci.ts): the failure, then the page before a heal.
 * Only those two are pictures: an API scenario's "requests" transcript is JSON.
 */
internal fun screenshotOf(evidence: List<StatusEvidence>): StatusEvidence? =
    evidence.firstOrNull { it.kind == "failure" } ?: evidence.firstOrNull { it.kind == "before-heal" }

/** What Open Replay steps through: the execution trace, or an API scenario's request transcript (saffron trace opens either). */
internal fun hasReplay(evidence: List<StatusEvidence>): Boolean =
    evidence.any { it.kind == "trace" || it.kind == "requests" }

/**
 * Reads, checks and copies a row's picture, then finds the copy in the VFS.
 * Disk work that takes long enough on a WSL path or a network share, with a
 * full-page picture, to freeze the IDE, so it is never run on the UI thread.
 */
internal fun screenshotToShow(base: String, shot: StatusEvidence, scenario: String): Screenshot {
    val file = projectFile(java.nio.file.Path.of(base), shot.file)?.toFile()
        ?: return Screenshot(problem = "Not opened: ${shot.file} is outside the project.")
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

/** A Last Run row's tooltip: after an import the report's text comes from CI, so it is escaped. [report]: lines from the run's report. */
internal fun attentionTooltip(a: StatusAttention, screenshot: Boolean, report: List<String> = emptyList()): String? = tooltipHtml(
    "${a.feature} › ${a.scenario}",
    a.failedStep?.let { "Failed step: $it" },
    a.error,
    if (screenshot) "Double-click to open the screenshot." else "No screenshot for this one; double-click opens the scenario.",
    *report.toTypedArray(),
)

/** The project's .saffron/reports/latest.json while it is the report of the run that started at [startedAt]; else null. Reads the disk. */
internal fun runReport(base: java.nio.file.Path, startedAt: String): JsonObject? = runCatching {
    JsonParser.parseString(java.nio.file.Files.readString(base.resolve(".saffron/reports/latest.json"))).asJsonObject
        .takeIf { it.get("startedAt")?.takeIf { s -> s.isJsonPrimitive }?.asString == startedAt }
}.getOrNull()

/** The runner's REPORT_ROWS (src/db/index.ts): the most rows of one check a report keeps. */
private const val REPORT_ROWS = 5

private fun text(e: JsonElement?): String? = e?.takeIf { it.isJsonPrimitive }?.asString
private fun objects(e: JsonElement?): List<JsonObject> = e?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject } ?: emptyList()
private fun texts(e: JsonElement?): List<String> = e?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull(::text) ?: emptyList()

/** The key a report scenario and a Last Run row share: feature (forward slashes), display name, browser. */
private fun scenarioKey(feature: String?, scenario: String?, browser: String?) = "${feature.orEmpty().replace('\\', '/')}\n${scenario.orEmpty()}\n${browser.orEmpty()}"
private fun keyOf(a: StatusAttention) = scenarioKey(a.feature, a.scenario, a.browser)
private fun keyOf(s: JsonObject) = scenarioKey(text(s.get("feature")), text(s.get("scenario")), text(s.get("browser")))

/** The report's scenarios by key, indexed once, the first of a key winning. */
private fun scenariosByKey(report: JsonObject): Map<String, JsonObject> =
    LinkedHashMap<String, JsonObject>().also { m -> for (s in objects(report.get("scenarios"))) m.putIfAbsent(keyOf(s), s) }

private fun instant(e: JsonElement?): java.time.Instant? = text(e)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }

/**
 * A scenario a run of named scenarios kept from the last report (the runner's foldIntoRun): run before this run
 * started, as the runner's report tells it apart ("from the run of ..."). Its rows are not this run's.
 */
internal fun keptFromEarlier(report: JsonObject, s: JsonObject): Boolean {
    val ran = instant(s.get("ranAt")) ?: return false
    val started = instant(report.get("startedAt")) ?: return false
    return ran.isBefore(started)
}

/** The runner's cleanupPanel (src/report/html.ts): rows database cleanup could not undo, named to remove by hand. */
private fun cleanupLines(rows: List<String>): List<String> =
    if (rows.isEmpty()) emptyList()
    else listOf("database cleanup", "Cleanup could not undo ${rows.size} row${if (rows.size == 1) "" else "s"} this scenario wrote: remove ${if (rows.size == 1) "it" else "them"} by hand. The verdict stands.") + rows

/**
 * What a run report keeps about one of its scenarios beyond status, as plain lines, in the runner's report's words
 * (src/report/html.ts): the rows each database check found (`dbRows` on a step result: the database it ran on, the
 * count, the statement, the first rows, masked as the runner kept them), then the rows cleanup could not undo
 * (`dbCleanup`), under "from the run of ..." for one kept from an earlier run. The report may be an imported CI
 * run's: read as untrusted, every value as text.
 */
private fun scenarioLines(report: JsonObject, scenario: JsonObject): List<String> {
    val checks = objects(scenario.get("steps")).flatMap { step ->
        objects(step.get("dbRows")).flatMap { c ->
            val count = c.get("count")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
            val rows = c.get("rows")?.takeIf { it.isJsonArray }?.asJsonArray?.take(REPORT_ROWS) ?: emptyList()
            val gherkin = text(step.get("gherkin"))?.lineSequence()?.first()?.trim().orEmpty()
            val sql = text(c.get("sql")).orEmpty().replace(Regex("\\s+"), " ").trim()
            val db = text(c.get("database"))?.let { "the $it database" } ?: "database"
            listOf("In \"$gherkin\", $db: $count row${if (count == 1) "" else "s"} · $sql") +
                rows.map { it.toString() } +
                (if (count > rows.size) listOf("... ${count - rows.size} more") else emptyList())
        }
    }
    val lines = checks + cleanupLines(texts(scenario.get("dbCleanup")))
    // Said whether or not it has database lines: the row itself is not this run's.
    if (!keptFromEarlier(report, scenario)) return lines
    val ran = instant(scenario.get("ranAt"))!!.toString().replace("T", " ").take(16)
    return listOf("From the run of $ran UTC: kept from an earlier run, this run did not run this scenario.") + lines
}

/** [scenarioLines] for the report's scenario a Last Run row lists; none when the report has no such scenario. */
internal fun reportStepLines(report: JsonObject?, a: StatusAttention): List<String> {
    report ?: return emptyList()
    return scenariosByKey(report)[keyOf(a)]?.let { scenarioLines(report, it) } ?: emptyList()
}

/**
 * The rows cleanup could not undo that no listed row shows, this run's only, as one row's count and tooltip lines:
 * the run's own (a Fixture's, kept for its feature's scenarios until the run ended, the report's top-level
 * `dbCleanup`), and those of scenarios this run ran that status does not list (a green one's). Null when none.
 */
internal fun cleanupSummary(report: JsonObject?, attention: List<StatusAttention>): Pair<Int, List<String>>? =
    report?.let { cleanupSummary(it, scenariosByKey(it), attention.mapTo(HashSet(), ::keyOf)) }

private fun cleanupSummary(report: JsonObject, byKey: Map<String, JsonObject>, listed: Set<String>): Pair<Int, List<String>>? {
    class Group(val fixture: Boolean, val feature: String, val scenario: String, val rows: List<String>)
    val groups = objects(report.get("dbCleanup")).map { c -> Group(c.get("fixture")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true, text(c.get("feature")).orEmpty(), text(c.get("scenario")).orEmpty(), texts(c.get("rows"))) } +
        byKey.values.filter { s -> s.get("dbCleanup")?.isJsonArray == true && keyOf(s) !in listed && !keptFromEarlier(report, s) }
            .map { s -> Group(false, text(s.get("feature")).orEmpty(), text(s.get("scenario")).orEmpty(), texts(s.get("dbCleanup"))) }
    val kept = groups.filter { it.rows.isNotEmpty() }
    val count = kept.sumOf { it.rows.size }
    if (count == 0) return null
    return count to (listOf("database cleanup", "Cleanup could not undo these rows: remove them by hand. The verdicts stand.") + kept.flatMap { g ->
        listOf("${if (g.fixture) "fixture" else "scenario"} \"${g.scenario}\" · ${g.feature}") +
            (if (g.fixture) listOf("Its rows were the feature's, kept for its scenarios until the run ended.") else emptyList()) + g.rows
    })
}

/**
 * What Last Run takes from the run's report, worked out off the UI thread: each listed row's lines by key, the listed
 * rows kept from an earlier run, the cleanup row, the teardown, and this run's own counts when the report holds earlier rows.
 */
internal class ReportLines(
    val byRow: Map<String, List<String>>,
    val cleanup: Pair<Int, List<String>>?,
    val teardownFailed: Boolean,
    private val kept: Set<String> = emptySet(),
    val thisRun: ThisRun? = null,
) {
    fun linesFor(a: StatusAttention): List<String> = byRow[keyOf(a)].orEmpty()
    fun keptFromEarlier(a: StatusAttention): Boolean = keyOf(a) in kept
}

/** The runner's report's own counts (kpiStrip, src/report/html.ts): `invocation.totals` when it holds rows kept from earlier runs. */
internal fun thisRunOf(report: JsonObject): ThisRun? {
    val totals = report.get("invocation")?.takeIf { it.isJsonObject }?.asJsonObject?.get("totals")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val earlier = objects(report.get("scenarios")).count { keptFromEarlier(report, it) }
    if (earlier == 0) return null
    fun n(key: String) = totals.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
    return ThisRun("${n("green")} passed, ${n("yellow")} pending review, ${n("red")} failed", earlier)
}

/** The run's report read and worked out for [attention], once: null when the report is not that run's. Reads the disk. */
internal fun reportLines(base: java.nio.file.Path, startedAt: String, attention: List<StatusAttention>): ReportLines? {
    val report = runReport(base, startedAt) ?: return null
    val byKey = scenariosByKey(report)
    val listed = attention.mapTo(HashSet(), ::keyOf)
    val byRow = listed.mapNotNull { key -> byKey[key]?.let { s -> scenarioLines(report, s).takeIf { it.isNotEmpty() }?.let { key to it } } }.toMap()
    val kept = listed.filterTo(HashSet()) { key -> byKey[key]?.let { keptFromEarlier(report, it) } == true }
    return ReportLines(byRow, cleanupSummary(report, byKey, listed), teardownFailed(report), kept, thisRunOf(report))
}

private class RunRow(
    val label: String,
    var detail: String = "",
    val icon: Icon? = null,
    var tooltip: String? = null,
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
    /** The status shown: a disk read started for an earlier one no longer applies. */
    private var rendered: ProjectStatus? = null
    /** The listed run's HTML report, project-relative. */
    private var reportHtml: String? = null
    private val tree = Tree(model).apply {
        accessibleContext.accessibleName = "Last run results"
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
            action("Open Replay", "Step through the selected scenario's run, or every traced scenario when none is selected: the page at every action, the network alongside (saffron trace)", AllIcons.Actions.Execute) { openReplay() },
            action("Open Scenario", "Jump to the selected scenario in its feature file", AllIcons.Actions.EditSource) { openScenario() },
            action("Open Report", "The last run's HTML report in the browser: every scenario and step", AllIcons.Nodes.PpWeb) { openReport() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
        tree.emptyText.text = "Loading…"
        object : DoubleClickListener() {
            // The scenario only when there is no picture: a picture that is
            // gone or replaced says so on the note, and jumping away would hide it.
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val a = selected() ?: return false
                return if (screenshotOf(a.evidence) != null) openScreenshot() else openScenario()
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
        // The row selected, found again below by its scenario, never by a name another feature's row shares: the reload
        // dropped it, and Open Replay then replayed every traced scenario (found in review).
        val was = selected()?.let(::attentionTraceSelector)
        root.removeAllChildren()
        rendered = status
        features = status?.features ?: emptyList()
        val run = status?.lastRun
        reportHtml = run?.reportHtml
        // Runners before 0.9.1 have no --run and would refuse the command itself.
        shownRun = run?.startedAt?.takeIf { it.isNotBlank() && runnerAtLeast(status?.version ?: "", 0, 9, 1) }
        val attention = run?.attention
        tree.emptyText.text = lastRunEmptyText(status)
        val started = run?.startedAt
        // Cut short with scenarios listed: the tree says so above them.
        if (run != null && run.interrupted && !attention.isNullOrEmpty()) {
            root.add(DefaultMutableTreeNode(RunRow("Run interrupted", "${run.notRun ?: "some"} not run", AllIcons.Actions.Suspend)))
        }
        for ((label, icon, inGroup) in listOf<Triple<String, Icon, (StatusAttention) -> Boolean>>(
            Triple("Failed", AllIcons.General.Error) { it.status == "red" && !attentionNotRun(it) },
            Triple("Not run", AllIcons.RunConfigurations.TestIgnored, ::attentionNotRun),
            // Yellow is healed, a first recording or a proposal not yet verified: all pending review.
            Triple("Pending review", AllIcons.General.Warning) { it.status == "yellow" },
        )) {
            val group = attention.orEmpty().filter(inGroup)
            if (group.isEmpty()) continue
            val parent = DefaultMutableTreeNode(RunRow(label, "${group.size}", AllIcons.Nodes.Folder))
            for (a in group) {
                val shot = screenshotOf(a.evidence)
                parent.add(
                    DefaultMutableTreeNode(
                        RunRow(
                            attentionLabel(a, run?.browsers != null),
                            attentionDetail(a),
                            if (shot != null) AllIcons.FileTypes.Image else icon,
                            attentionTooltip(a, shot != null),
                            a,
                        ),
                    ),
                )
            }
            root.add(parent)
        }
        // A run of named scenarios folded into the earlier report: its own counts head the rows, which may be earlier runs'.
        val folded = run?.let(::thisRun)
        if (folded != null && !attention.isNullOrEmpty()) root.insert(headline(folded), 0)
        model.reload()
        TreeUtil.expandAll(tree)
        // The rows each database check found are in the run's report, not in status: added to the tooltips once read.
        // What the status leaves out, from the run's own report (one read): an all-green run whose teardown failed (the
        // report keeps the hooks), the rows each database check found (in the rows' tooltips), and the rows cleanup could
        // not undo, in a row of their own when no listed row shows them.
        if (run != null && attention != null && !started.isNullOrBlank()) {
            val base = projectRoot
            offUiThread(project, alive, { reportLines(base, started, attention) }) { report ->
                if (report == null || rendered !== status) return@offUiThread
                // An older runner's status has no counts of this run's own: the report's.
                val own = folded ?: report.thisRun
                if (attention.isEmpty() && !run.interrupted) tree.emptyText.text = lastRunEmptyText(status, report.teardownFailed, own)
                if (folded == null && report.thisRun != null && attention.isNotEmpty()) model.insertNodeInto(headline(report.thisRun), root, 0)
                if (report.byRow.isNotEmpty()) {
                    for (node in root.depthFirstEnumeration().toList()) {
                        val r = (node as? DefaultMutableTreeNode)?.userObject as? RunRow ?: continue
                        val a = r.attention ?: continue
                        report.linesFor(a).takeIf { it.isNotEmpty() }?.let { r.tooltip = attentionTooltip(a, screenshotOf(a.evidence) != null, it) }
                        // The runner's report marks it "from the run of ..." too: this run did not run it.
                        if (report.keptFromEarlier(a)) {
                            r.detail = listOf(r.detail, "from an earlier run").filter { it.isNotEmpty() }.joinToString(" · ")
                            model.nodeChanged(node)
                        }
                    }
                }
                report.cleanup?.let { (count, lines) ->
                    // A run that lists nothing (all green, interrupted, teardown failed): what the empty tree said stays said
                    // above the row. Inserted, not reloaded: the row selected stays selected.
                    if (root.childCount == 0) model.insertNodeInto(DefaultMutableTreeNode(RunRow(tree.emptyText.text, "", AllIcons.General.Warning)), root, 0)
                    model.insertNodeInto(DefaultMutableTreeNode(RunRow("Database cleanup", "$count row${if (count == 1) "" else "s"} left", AllIcons.General.Warning, tooltipHtml(*lines.toTypedArray()))), root, root.childCount)
                    tree.expandPath(TreePath(root.path))
                }
            }
        }
        was?.let { w -> TreeUtil.findNode(root) { (it.userObject as? RunRow)?.attention?.let(::attentionTraceSelector) == w } }?.let { tree.selectionPath = TreePath(it.path) }
    }

    private fun headline(own: ThisRun) = DefaultMutableTreeNode(
        RunRow("This run: ${own.counts}", "${own.earlierRows} more in the report from earlier runs", AllIcons.General.Information,
            "A run of named scenarios keeps the earlier report's other rows; those marked \"from an earlier run\" are not this run's."),
    )

    private fun selected(): StatusAttention? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? RunRow)?.attention

    /**
     * `saffron trace <scenario>` in the Run tool window; it prints the replay's URL and opens it.
     * With no row selected (an all-green run lists none), `saffron trace` opens every traced
     * scenario of the run, or says how to keep traces when it kept none.
     */
    private fun openReplay(): Boolean {
        val a = selected()
        if (a != null && !hasReplay(a.evidence)) {
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
            it.paths = a?.let(::attentionTraceSelector) ?: ""
            // The run these rows list: the replay refuses a later one.
            it.extraArgs = shownRun?.let { run -> "--run $run" } ?: ""
        }
        return true
    }

    private fun openScreenshot(): Boolean {
        val a = selected() ?: return false
        // A trace is not a picture; the pictures come first, the replay has its own action.
        val shot = screenshotOf(a.evidence) ?: return false
        val base = projectRoot.toString()
        clearNote()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val result = screenshotToShow(base, shot, a.scenario)
            app.invokeLater({
                // Names its scenario: the selection may have moved on meanwhile.
                result.problem?.let { note.text = it }
                result.file?.let { OpenFileDescriptor(project, it).navigate(true) }
            }, project.disposed)
        }
        return true
    }

    private fun openScenario(): Boolean {
        val a = selected() ?: return false
        clearNote()
        return openScenario(features, a.feature, a.baseScenario)
    }

    /** The run's HTML report, in the system browser: the full story, every scenario and step. */
    private fun openReport() {
        val html = reportHtml
        val root = projectRoot
        clearNote()
        // The path is checked and the file looked for off the UI thread: both read the disk.
        offUiThread(project, alive, { html?.let { projectFile(root, it) }?.takeIf { java.nio.file.Files.exists(it) } }) { report ->
            if (report == null) note.text = "No HTML report for the last run. Run something first." else BrowserUtil.browse(report.toUri())
        }
    }
}
