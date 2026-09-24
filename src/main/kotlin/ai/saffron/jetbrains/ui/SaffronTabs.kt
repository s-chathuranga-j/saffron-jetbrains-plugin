package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronRunner
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JSplitPane

/** Shared scaffolding for the status-driven tabs. */
abstract class StatusTab(protected val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {

    protected val note = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(4, 8) }

    init {
        project.messageBus.connect(parent).subscribe(SaffronStatusService.TOPIC, StatusListener { load -> onStatus(load) })
    }

    /** Called after the UI is built; applies whatever the service already holds. */
    protected fun start() {
        val service = SaffronStatusService.getInstance(project)
        onStatus(service.latest)
        service.refresh()
    }

    private fun onStatus(load: StatusLoad) {
        val status = load.status
        if (status == null) {
            note.text = load.error?.let { "Status unavailable: $it" } ?: "Loading…"
            render(null)
            return
        }
        note.text = if (status.packageInstalled) "" else "saffron-ai is not installed here (npm i -D saffron-ai); status came from npx."
        render(status)
    }

    protected abstract fun render(status: ProjectStatus?)

    protected fun action(text: String, description: String, icon: Icon, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    protected fun toolbar(vararg actions: AnAction): JPanel {
        val bar = ActionManager.getInstance().createActionToolbar("SaffronToolWindow", DefaultActionGroup(*actions), true)
        bar.targetComponent = this
        return JPanel(BorderLayout()).apply { add(bar.component, BorderLayout.CENTER) }
    }

    protected fun refreshStatus() = SaffronStatusService.getInstance(project).refresh()
}

/** Pending proposals: tick, read the narrative, accept or reject. */
class ProposalsTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val list = CheckBoxList<StatusProposal>()
    private val details = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(6) }
    private var items: List<StatusProposal> = emptyList()
    /**
     * `saffron diff` output per proposal VERSION: the evidence behind the
     * narrative. A new run overwrites a proposal under the same file name, so
     * the file name alone would show the old diff beside the new narrative,
     * and Accept would then promote something the reviewer never read. Only
     * touched on the EDT.
     */
    private val diffs = mutableMapOf<String, String>()
    /** Bumped on every refresh; a diff loaded for an earlier one is discarded. */
    private var generation = 0

    private fun diffKey(p: StatusProposal) = "${p.file}|${p.createdAt}"

    init {
        toolbar = toolbar(
            action("Accept Selected", "saffron accept <ticked files>", AllIcons.Actions.Commit) { act("accept", ticked()) },
            action("Reject Selected", "saffron reject <ticked files>", AllIcons.Actions.Cancel) { act("reject", ticked()) },
            action("Accept All", "saffron accept --all (UNVERIFIED proposals are skipped)", AllIcons.Actions.Checked) { acceptAll() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
        list.setEmptyText("No proposals pending review")
        list.addListSelectionListener {
            val i = list.selectedIndex
            val selected = if (i >= 0) list.getItemAt(i) else null
            details.text = describe(selected)
            details.caretPosition = 0
            if (selected != null) loadDiff(selected)
        }
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(list), JBScrollPane(details)).apply {
            resizeWeight = 0.55
            border = null
        }
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        val ticked = ticked().map { it.file }.toSet()
        items = status?.proposals ?: emptyList()
        // The committed cache a diff was computed against may have changed
        // too (an accept, a pull), so a refresh invalidates every diff.
        generation++
        diffs.clear()
        list.clear()
        for (p in items) {
            val verified = when (p.verified) {
                true -> "verified"
                false -> "UNVERIFIED"
                null -> "unchecked"
            }
            val state = if (p.stale != null) "STALE · $verified" else "${p.mode} · $verified"
            list.addItem(p, "${p.feature.substringAfterLast('/')} › ${p.scenario}   $state", p.file in ticked)
        }
        if (items.isEmpty()) details.text = ""
    }

    private fun ticked(): List<StatusProposal> = items.filter { list.isItemSelected(it) }

    /**
     * The action list the reviewer is approving. The narrative is the agent's
     * account of what it did; this is what it actually recorded, against what
     * is committed today.
     */
    private fun loadDiff(proposal: StatusProposal) {
        val key = diffKey(proposal)
        diffs[key]?.let { cached ->
            details.text = describe(proposal) + cached
            details.caretPosition = 0
            return
        }
        val base = project.basePath ?: return
        val loadedFor = generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                val output = ExecUtil.execAndGetOutput(SaffronCommand.base(base).withParameters("diff", proposal.file), 60_000)
                if (output.exitCode == 0 && output.stdout.isNotBlank()) {
                    "\n\nWHAT THIS PROPOSAL CHANGES\n\n" + output.stdout.trim()
                } else {
                    "\n\n(could not load the diff: " + output.stderr.trim().lines().lastOrNull().orEmpty() + ")"
                }
            } catch (e: Exception) {
                "\n\n(could not load the diff: " + (e.message ?: e.toString()) + ")"
            }
            ApplicationManager.getApplication().invokeLater({
                // A refresh happened while this ran: the answer describes a
                // proposal or a committed cache that may no longer exist.
                if (loadedFor != generation) return@invokeLater
                diffs[key] = text
                val selected = if (list.selectedIndex >= 0) list.getItemAt(list.selectedIndex) else null
                if (selected != null && diffKey(selected) == key) {
                    details.text = describe(selected) + text
                    details.caretPosition = 0
                }
            }, project.disposed)
        }
    }

    /**
     * Accepting or rejecting what is ticked. An empty selection does nothing:
     * blank paths mean `--all` to the runner, so this used to accept every
     * proposal when the reviewer had ticked none of them.
     */
    private fun act(command: String, selected: List<StatusProposal>) {
        if (selected.isEmpty()) {
            note.text = "Tick the proposals to $command first, or use Accept All."
            return
        }
        SaffronRunner.execute(project, "Saffron: $command ${selected.size} proposal(s)") {
            it.command = command
            it.paths = SaffronCommand.joinPaths(selected.map { p -> p.file })
        }
    }

    /** The deliberate bulk action, never reachable by leaving the list untouched. */
    private fun acceptAll() {
        SaffronRunner.execute(project, "Saffron: accept all") {
            it.command = "accept"
            it.paths = ""
        }
    }

    private fun describe(p: StatusProposal?): String {
        p ?: return ""
        val b = StringBuilder()
        b.append(p.feature).append(" › ").append(p.scenario).append('\n')
        b.append("file: ").append(p.file).append('\n')
        b.append("mode: ").append(p.mode).append(" · created ").append(p.createdAt)
        if (p.recordedFor != null) b.append(" · recorded for ").append(p.recordedFor)
        b.append('\n')
        p.stale?.let { b.append("STALE, cannot be accepted: ").append(it).append(". Run the scenario again for a fresh proposal, or reject this one.\n") }
        if (p.unbound == true) b.append("Filed by an older runner: changes to the scenario or the recording since cannot be detected.\n")
        b.append("proof replay: ").append(
            when (p.verified) {
                true -> "verified, replays at zero AI"
                false -> "FAILED: ${p.proofError ?: "see the proposal file"}"
                null -> "not run"
            },
        ).append('\n')
        b.append("agent: ").append(p.aiCalls).append(" AI calls")
        p.costUsd?.let { b.append(" · ≈ $").append("%.2f".format(it)).append(" at API rates") }
        b.append("\n\n").append(p.narrative).append('\n')
        if (p.adaptations.isNotEmpty()) {
            b.append("\nAdaptations:\n")
            for (a in p.adaptations) b.append("  ⚠ ").append(a).append('\n')
        }
        p.suggestedFeatureEdit?.let { b.append("\nSuggested feature edit:\n").append(it).append('\n') }
        return b.toString()
    }
}

/** Tags with scenario counts; tick and run with --filter. */
class TagsTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val list = CheckBoxList<StatusTag>()
    private val replayOnly = JBCheckBox("Replay only (no AI)")
    private val headed = JBCheckBox("Headed browser")
    private var items: List<StatusTag> = emptyList()

    init {
        toolbar = toolbar(
            action("Run Tagged", "saffron run --filter <ticked tags> (any of them)", AllIcons.Actions.Execute) { run() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
        list.setEmptyText("No @tags in the feature files")
        val options = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 6)
            add(replayOnly)
            add(javax.swing.Box.createHorizontalStrut(12))
            add(headed)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(options, BorderLayout.NORTH); add(note, BorderLayout.SOUTH) }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        val ticked = items.filter { list.isItemSelected(it) }.map { it.tag }.toSet()
        items = status?.tags ?: emptyList()
        list.clear()
        for (t in items) list.addItem(t, "${t.tag}   ${t.scenarios} scenario${if (t.scenarios == 1) "" else "s"}", t.tag in ticked)
    }

    private fun run() {
        val tags = items.filter { list.isItemSelected(it) }.map { it.tag }
        if (tags.isEmpty()) {
            note.text = "Tick one or more tags first."
            return
        }
        SaffronRunner.execute(project, "Saffron: run ${tags.joinToString(",")}") {
            it.command = "run"
            it.paths = ""
            it.tags = tags.joinToString(",")
            it.replayOnly = replayOnly.isSelected
            it.headed = headed.isSelected
        }
    }
}

/** The HTML report, embedded. */
class DashboardTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val placeholder = JBLabel("", JBLabel.CENTER)
    private var reportPath: Path? = null

    init {
        toolbar = toolbar(
            action("Reload", "Reload the report", AllIcons.Actions.Refresh) { refreshStatus() },
            action("Open in Browser", "Open the report in the system browser", AllIcons.Nodes.PpWeb) { reportPath?.let { BrowserUtil.browse(it.toUri()) } },
        )
        browser?.let { com.intellij.openapi.util.Disposer.register(parent, it) }
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(browser?.component ?: placeholder, BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        val base = project.basePath
        val rel = status?.lastRun?.reportHtml
        val path = if (base != null && rel != null) Path.of(base, rel) else null
        reportPath = path?.takeIf { Files.exists(it) }
        val b = browser
        if (b == null) {
            placeholder.text = if (reportPath != null) "This IDE build has no embedded browser. Use Open in Browser." else "No report yet: run something first."
            return
        }
        val target = reportPath
        if (target == null) {
            b.loadHTML("<html><body style=\"font-family:sans-serif;color:#888;padding:24px\">No report yet. Run a feature file and the report appears here.</body></html>")
        } else {
            b.loadURL(target.toUri().toString())
        }
    }
}
