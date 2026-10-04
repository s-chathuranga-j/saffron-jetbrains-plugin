package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronRunner
import com.intellij.icons.AllIcons
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JList
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

    /** What the status says about itself (unavailable, not installed); a tab's own messages give way to it. */
    private var statusNote = ""

    private fun onStatus(load: StatusLoad) {
        // Loaded for a project chosen before the one shown now: not this one's.
        if (load.root != null && load.root != projectRoot) return
        val status = load.status
        statusNote = when {
            status == null && load.error == SaffronStatusService.UNTRUSTED -> load.error
            status == null -> load.error?.let { "Status unavailable: $it" } ?: "Loading…"
            status.packageInstalled -> ""
            else -> "saffron-ai is not installed here (npm i -D saffron-ai); status came from npx."
        }
        note.text = statusNote
        render(status)
    }

    /** Takes down a tab's own message once it no longer applies, putting the status's note back. */
    protected fun clearNote() {
        note.text = statusNote
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

    /** The Saffron project the tabs show. */
    protected val projectRoot: Path get() = SaffronStatusService.getInstance(project).root

    /**
     * Opens [scenario] (its name in the feature file) at its line, or the file's top when it is not found.
     * [root] is the Saffron project the row was listed for, which may no longer be the one shown.
     */
    protected fun openScenario(features: List<StatusFeature>, feature: String, scenario: String, root: Path = projectRoot): Boolean {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(feature)) ?: return false
        val line = features.firstOrNull { it.path == feature }?.scenarios?.firstOrNull { it.name == scenario }?.line
        OpenFileDescriptor(project, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }
}

/** Pending proposals: tick, read the narrative, accept or reject. */
class ProposalsTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val list = CheckBoxList<StatusProposal>()
    /** Proposal files the runner could not read: not decidable here, shown with the reason. */
    private val unreadable = JBList<UnreadableProposal>().apply {
        cellRenderer = object : ColoredListCellRenderer<UnreadableProposal>() {
            override fun customizeCellRenderer(list: JList<out UnreadableProposal>, value: UnreadableProposal, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = AllIcons.General.Warning
                append(value.file)
                append("  ${value.problem}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = "${value.file}\n${value.problem}"
            }
        }
        isVisible = false
    }
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
            add(JPanel(BorderLayout()).apply { add(note, BorderLayout.NORTH); add(unreadable, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        val broken = status?.unreadableProposals ?: emptyList()
        unreadable.setListData(broken.toTypedArray())
        unreadable.isVisible = broken.isNotEmpty()
        // A tick stays with the proposal that was ticked: one a later run filed
        // under the same name comes back unticked, unseen.
        val ticked = ticked().map { it.target }.toSet()
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
            val state = (if (p.stale != null) "STALE · $verified" else "${p.mode} · $verified") + (p.importedFrom?.let { " · ${it.label}" } ?: "")
            list.addItem(p, "${p.feature.substringAfterLast('/')} › ${p.scenario}   $state", p.target in ticked)
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
        val base = projectRoot.toString()
        val loadedFor = generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                val output = ExecUtil.execAndGetOutput(SaffronCommand.base(base).withParameters("diff", proposal.file), 60_000)
                if (output.exitCode == 0 && output.stdout.isNotBlank()) {
                    "\n\nWHAT THIS PROPOSAL CHANGES\n\n" + output.stdout.trim()
                } else {
                    "\n\n(could not load the diff: " + runnerError(output.stderr) + ")"
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
            it.paths = SaffronCommand.joinPaths(selected.map { p -> p.target })
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
        p.importedFrom?.let { b.append(it.description).append('\n') }
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
            action("Run Tagged", "saffron run --filter <ticked tags> (any of them; the CLI also takes \"@a and not @b\")", AllIcons.Actions.Execute) { run() },
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
