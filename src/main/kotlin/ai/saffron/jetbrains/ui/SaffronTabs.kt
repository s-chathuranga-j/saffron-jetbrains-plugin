package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronRunner
import com.intellij.icons.AllIcons
import com.intellij.util.execution.ParametersListUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
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

/**
 * [work] on a pooled thread (it reads the disk), then [then] with its answer on the UI thread, unless [alive]
 * (the tab asking) or [project] was disposed meanwhile.
 */
internal fun <T> offUiThread(project: Project, alive: CheckedDisposable, work: () -> T, then: (T) -> Unit) {
    val app = ApplicationManager.getApplication()
    app.executeOnPooledThread {
        val answer = work()
        app.invokeLater({ then(answer) }) { alive.isDisposed || project.isDisposed }
    }
}

/**
 * [rel] inside a trusted root of the project at [root], or null when it leads out of
 * them all (`../` or an absolute path): paths from a report, which an imported CI run
 * wrote, are never trusted. The trusted roots are the project, its test folder (which
 * may sit outside it, as `"features": "../e2e"`) and its .saffron folder. The runner
 * follows linked folders inside the test folder wherever they lead, so a path there
 * counts as written; anywhere else a link is not followed, so a path counts where it
 * really leads.
 */
internal fun projectFile(root: Path, rel: String): Path? {
    val base = root.toAbsolutePath().normalize()
    // A report from a CI machine on Windows names "features\login.saffron": read with forward slashes where no
    // file has that name, as the runner reads it.
    val slashed = rel.replace('\\', '/')
    val full = runCatching { base.resolve(rel).normalize() }.getOrNull()?.takeIf { rel.isNotEmpty() }
        ?.let { p -> if (slashed != rel && !java.nio.file.Files.exists(p)) runCatching { base.resolve(slashed).normalize() }.getOrNull() ?: p else p }
        ?: return null
    val features = SaffronProjectScan.featuresDir(base)
    val trusted = listOf(base, features, base.resolve(".saffron"))
    // The real path, through its nearest existing folder for a file not written yet.
    fun real(p: Path): Path = runCatching { p.toRealPath() }.getOrNull() ?: p.parent?.let { real(it).resolve(p.fileName.toString()) } ?: p
    // As the file system compares names: case-insensitively on macOS and Windows.
    fun inside(p: Path, roots: List<Path>) = roots.any { com.intellij.openapi.util.io.FileUtil.isAncestor(it.toString(), p.toString(), true) }
    return full.takeIf { inside(it, listOf(features)) || inside(real(it), trusted + trusted.map(::real)) }
}

/** Shared scaffolding for the status-driven tabs. */
abstract class StatusTab(protected val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {

    protected val note = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(4, 8) }

    /** Disposed with the tab: work it started off the UI thread then touches it no more. */
    protected val alive: CheckedDisposable = Disposer.newCheckedDisposable(parent)

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

    /** Says a path from a report was not opened because it leads out of the project. */
    protected fun refused(rel: String): Boolean {
        note.text = "Not opened: $rel is outside the project."
        return false
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
        // The status lists paths with forward slashes; a report written on Windows names them with backslashes.
        val line = features.firstOrNull { it.path == feature || it.path == feature.replace('\\', '/') }?.scenarios?.firstOrNull { it.name == scenario }?.line
        openProjectFile(root, feature) { OpenFileDescriptor(project, it, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true) }
        return true
    }

    /**
     * [rel] of the project at [root], checked by [projectFile] and found in the VFS off the UI thread (both read the
     * disk, slow on a WSL path or a network share), then [open]ed on it; one leading out of the project is refused there.
     */
    protected fun openProjectFile(root: Path, rel: String, open: (com.intellij.openapi.vfs.VirtualFile) -> Unit) {
        offUiThread(project, alive, {
            val file = projectFile(root, rel)
            file to file?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        }) { (file, vf) ->
            when {
                file == null -> refused(rel)
                vf == null -> note.text = "Not opened: $rel is not on disk."
                else -> open(vf)
            }
        }
    }
}

/** Pending proposals: tick, read the narrative, accept or reject. */
class ProposalsTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val list = CheckBoxList<StatusProposal>().apply { accessibleContext.accessibleName = "Pending proposals" }
    /** Proposal files the runner could not read: not decidable here, shown with the reason. */
    private val unreadable = JBList<UnreadableProposal>().apply {
        accessibleContext.accessibleName = "Unreadable proposals"
        cellRenderer = object : ColoredListCellRenderer<UnreadableProposal>() {
            override fun customizeCellRenderer(list: JList<out UnreadableProposal>, value: UnreadableProposal, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = AllIcons.General.Warning
                append(value.file)
                append("  ${value.problem}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = tooltipHtml(value.file, value.problem)
            }
        }
        isVisible = false
    }
    private val details = JBTextArea().apply { accessibleContext.accessibleName = "Proposal details"; isEditable = false; lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(6) }
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
    /** `saffron diff` in flight: destroyed with the tab, and when another proposal is selected. */
    private val processes = ProcessTracker(parent)
    /** The diff loading for the selected proposal; the field is only touched on the EDT. */
    private var diffLoading: DiffLoad? = null
    private var diffLoadingKey: String? = null
    private var diffLoadingFor = -1

    private fun diffKey(p: StatusProposal) = "${p.file}|${p.createdAt}"

    /** `saffron diff` for a proposal file; built off the UI thread (it reads the disk and starts a process). */
    internal var diffCommand: (String, String) -> GeneralCommandLine = { base, file -> SaffronCommand.base(base).withParameters("diff", file) }

    init {
        toolbar = toolbar(
            action("Accept Selected", "saffron accept <ticked files>", AllIcons.Actions.Commit) { act("accept", ticked()) },
            action("Reject Selected", "saffron reject <ticked files>", AllIcons.Actions.Cancel) { act("reject", ticked()) },
            action("Accept All", PROPOSALS_ACCEPT_ALL_TIP, AllIcons.Actions.Checked) { acceptAll() },
            action("Accept a StepSet's Steps", "saffron accept <ticked file> --step-set <name>: file one StepSet's steps for other scenarios to reuse, the proposal left pending", AllIcons.Nodes.Folder) { acceptStepSet() },
            action("Open Draft Pull Request", "saffron export --branch <name>: push the pending proposals to a new branch and open a draft pull request", AllIcons.Vcs.Push) { exportPullRequest() },
            action("Export Bundle", "saffron export: the pending proposals, the last run's report, screenshots and traces in one bundle", AllIcons.Actions.Download) { exportBundle() },
            action("Eject to Playwright", "saffron eject: write the committed recordings as Playwright specs in the project", AllIcons.Actions.Execute) { eject() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
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
        // So does the selection, the proposal being read: a reload emptied the details pane (found in review).
        val reading = list.selectedIndex.takeIf { it >= 0 }?.let { list.getItemAt(it)?.target }
        items = status?.proposals ?: emptyList()
        // No status (loading, or unavailable) says nothing about what is pending (found in review).
        list.setEmptyText(if (status == null) "Loading…" else "No proposals pending review")
        // The committed cache a diff was computed against may have changed
        // too (an accept, a pull), so a refresh invalidates every diff.
        generation++
        diffs.clear()
        list.clear()
        for (p in items) {
            val verified = when (p.verified) {
                true -> if (p.failedRows.isNullOrEmpty()) "verified" else "FAILS A ROW"
                false -> "UNVERIFIED"
                null -> "unchecked"
            }
            val state = (if (p.stale != null) "STALE · $verified" else "${p.mode} · $verified") + (p.importedFrom?.let { " · ${it.label}" } ?: "") +
                (if (p.heldFromAcceptAll) " · held from Accept All" else "")
            list.addItem(p, "${p.feature.replace('\\', '/').substringAfterLast('/')} › ${p.scenario}   $state", p.target in ticked)
        }
        if (items.isEmpty()) details.text = ""
        items.indexOfFirst { it.target == reading }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
    }

    private fun ticked(): List<StatusProposal> = items.filter { list.isItemSelected(it) }

    /**
     * The action list the reviewer is approving. The narrative is the agent's
     * account of what it did; this is what it actually recorded, against what
     * is committed today.
     */
    private fun loadDiff(proposal: StatusProposal) {
        val key = diffKey(proposal)
        // Still loading for this very proposal (the selection fired again): let it finish.
        if (diffLoading != null && diffLoadingKey == key && diffLoadingFor == generation) return
        // Another proposal selected: the last one's diff is no longer wanted.
        diffLoading?.cancel()
        diffLoading = null
        diffs[key]?.let { cached ->
            details.text = describe(proposal, cached) + cached
            details.caretPosition = 0
            return
        }
        val base = projectRoot.toString()
        val loadedFor = generation
        val load = DiffLoad()
        val command = diffCommand
        diffLoading = load
        diffLoadingKey = key
        diffLoadingFor = loadedFor
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                // Built here, not in the selection listener: base() reads the disk and the login-shell environment, and the handler starts the process.
                val handler = load.start { CapturingProcessHandler(command(base, proposal.file)).apply { setShouldDestroyProcessRecursively(true) } }
                val output = handler?.let { processes.tracked { p -> p.runProcess(60_000, true) }(it) }
                if (output == null) {
                    "" // Cancelled before it started: never shown, never cached.
                } else if (output.exitCode == 0 && output.stdout.isNotBlank()) {
                    DIFF_HEADING + output.stdout.trim()
                } else {
                    "\n\n(could not load the diff: " + commandProblem(output, "diff", 60) + ")"
                }
            } catch (e: Exception) {
                "\n\n(could not load the diff: " + (e.message ?: e.toString()) + ")"
            }
            ApplicationManager.getApplication().invokeLater({
                // Cancelled by another selection: its answer is a killed process's, never cached.
                if (diffLoading !== load) return@invokeLater
                diffLoading = null
                // A refresh happened while this ran: the answer describes a
                // proposal or a committed cache that may no longer exist.
                if (loadedFor != generation) return@invokeLater
                diffs[key] = text
                val selected = if (list.selectedIndex >= 0) list.getItemAt(list.selectedIndex) else null
                if (selected != null && diffKey(selected) == key) {
                    details.text = describe(selected, text) + text
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

    /** One ticked proposal's StepSet, chosen when it recorded several, filed for reuse; the proposal stays pending. */
    private fun acceptStepSet() {
        val selected = ticked()
        if (selected.size != 1) {
            note.text = "Tick the one proposal whose StepSet steps you want to file for reuse."
            return
        }
        val p = selected[0]
        val sets = p.stepSets.orEmpty()
        if (sets.isEmpty()) {
            note.text = "That proposal recorded no StepSet's steps (or this saffron-ai does not list them: update saffron-ai)."
            return
        }
        val name = if (sets.size == 1) sets[0] else Messages.showEditableChooseDialog("The StepSet whose steps other scenarios reuse:", "Accept a StepSet's Steps", null, sets.toTypedArray(), sets[0], null) ?: return
        SaffronRunner.execute(project, "Saffron: accept StepSet $name") {
            it.command = "accept"
            it.paths = SaffronCommand.joinPaths(listOf(p.target))
            it.extraArgs = ParametersListUtil.join(listOf("--step-set", name))
        }
    }

    private fun exportBundle() = SaffronRunner.execute(project, "Saffron: export") { it.command = "export" }

    /** Asked for a branch, then asked again: it pushes to origin and opens a pull request. */
    private fun exportPullRequest() {
        val branch = Messages.showInputDialog(project, "Branch for the pending proposals, pushed to origin with a draft pull request:", "Open Draft Pull Request", null, "saffron/proposals-${java.time.LocalDate.now()}", null)?.trim() ?: return
        if (!SaffronCommand.isBranchName(branch)) {
            note.text = "A branch name takes letters, digits, '.', '_', '-' and '/', not starting with '-'."
            return
        }
        if (Messages.showYesNoDialog(project, "Push the pending proposals to \"$branch\" on origin and open a draft pull request?", "Open Draft Pull Request", "Push and Open", "Cancel", Messages.getWarningIcon()) != Messages.YES) return
        SaffronRunner.execute(project, "Saffron: export to $branch") {
            it.command = "export"
            it.extraArgs = ParametersListUtil.join(listOf("--branch", branch))
        }
    }

    /** Asked first: it writes files into the project. The open feature file alone, or every one. */
    private fun eject() {
        val open = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.takeIf { it.extension == "saffron" || it.extension == "feature" }
        // The open file's own Saffron project, which may be nested in the one the tool window shows: eject reads its
        // recordings and config and writes its playwright/ folder, as VS Code does.
        val service = SaffronStatusService.getInstance(project)
        val shown = service.root
        val roots = service.roots
        val base = project.basePath?.let { b -> java.nio.file.Paths.get(b) }
        // The root is found off the UI thread: it looks for saffron.config.json files on the disk.
        offUiThread(project, alive, {
            // Only a Saffron project: rootFor falls back to the IDE project, which is none when the projects are nested in
            // it, and eject would write specs there for another tool's file (found in review).
            val root = open?.let { ejectRoot(java.nio.file.Paths.get(it.path), base, shown, roots) } ?: shown
            // Forward slashes on every platform, as VS Code sends them and the runner reads them.
            root to open?.let { runCatching { com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(root.relativize(java.nio.file.Paths.get(it.path)).toString()) }.getOrNull() }?.takeIf { inProject(it) }
        }) { (root, rel) -> confirmEject(root, rel) }
    }

    private fun confirmEject(root: Path, rel: String?) {
        val ask = "Write the committed recordings as Playwright specs in the project's playwright/ folder? Files saffron eject did not write are kept."
        val paths = if (rel != null) {
            when (Messages.showYesNoCancelDialog(project, ask, "Eject to Playwright", "This File ($rel)", "Every Feature File", "Cancel", Messages.getQuestionIcon())) {
                Messages.YES -> SaffronCommand.joinPaths(listOf(rel))
                Messages.NO -> ""
                else -> return
            }
        } else if (Messages.showYesNoDialog(project, ask, "Eject to Playwright", "Write Specs", "Cancel", Messages.getQuestionIcon()) == Messages.YES) "" else return
        SaffronRunner.execute(project, "Saffron: eject") {
            it.command = "eject"
            it.paths = paths
            if (paths.isNotEmpty()) it.workingDirectory = root.toString()
        }
    }

    /** The deliberate bulk action, never reachable by leaving the list untouched. */
    private fun acceptAll() {
        if (!SaffronRunner.confirmAcceptAll(project)) return
        SaffronRunner.execute(project, "Saffron: accept all") {
            it.command = "accept"
            it.paths = ""
        }
    }

    /** [diff] is what follows in the pane: a loaded diff lists the feature edits itself, so they are not listed twice. */
    private fun describe(p: StatusProposal?, diff: String = ""): String {
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
                true -> if (p.failedRows.isNullOrEmpty()) "verified, replays at zero AI" else "passed, then FAILED on ${p.failedRows.joinToString(", ")}"
                false -> "FAILED: ${p.proofError ?: "see the proposal file"}"
                null -> "not run"
            },
        ).append('\n')
        b.append("agent: ").append(p.aiCalls).append(" AI calls")
        p.costUsd?.takeIf { it > 0 }?.let { b.append(" · ≈ $").append("%.2f".format(it)).append(" at API rates") }
        b.append("\n\n").append(p.narrative).append('\n')
        if (p.adaptations.isNotEmpty()) {
            b.append("\nAdaptations:\n")
            for (a in p.adaptations) b.append("  ⚠ ").append(a).append('\n')
        }
        p.suggestedFeatureEdit?.let { b.append("\nSuggested feature edit:\n").append(it).append('\n') }
        b.append(editsBeside(p.featureEdits, diff))
        return b.toString()
    }
}

/** Tags with scenario counts; tick and run with --filter. */
class TagsTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    private val list = CheckBoxList<StatusTag>().apply { accessibleContext.accessibleName = "Tags" }
    private val replayOnly = JBCheckBox("Replay only (no AI)")
    private val headed = JBCheckBox("Headed browser")
    private var items: List<StatusTag> = emptyList()

    init {
        toolbar = toolbar(
            action("Run Tagged", "saffron run --filter <ticked tags> (any of them; the CLI also takes \"@a and not @b\")", AllIcons.Actions.Execute) { run() },
            action("Refresh", "Reload the status", AllIcons.Actions.Refresh) { refreshStatus() },
        )
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
        // No status (loading, or unavailable) says nothing about the files' tags.
        list.setEmptyText(if (status == null) "Loading…" else "No @tags in the feature files")
        list.clear()
        for (t in items) list.addItem(t, "${t.tag}   ${t.scenarios} scenario${if (t.scenarios == 1) "" else "s"}", t.tag in ticked)
    }

    private fun run() {
        val ticked = items.filter { list.isItemSelected(it) }.map { it.tag }
        if (ticked.isEmpty()) {
            note.text = "Tick one or more tags first."
            return
        }
        val tags = ticked.filter(::filterableTag)
        note.text = (ticked - tags.toSet()).takeIf { it.isNotEmpty() }
            ?.let { "Skipped ${it.joinToString(", ")}: --filter cannot name a tag with a comma, pipe, space or stray parenthesis." } ?: ""
        if (tags.isEmpty()) return
        SaffronRunner.execute(project, "Saffron: run ${tags.joinToString(",")}") {
            it.command = "run"
            it.paths = ""
            it.tags = tags.joinToString(",")
            it.replayOnly = replayOnly.isSelected
            it.headed = headed.isSelected
        }
    }
}

/** A tag --filter reads as one token (the runner's tokenizer): `@issue(42)` is fine, `@a,b` or `@a|b` is not. */
internal fun filterableTag(tag: String): Boolean = Regex("[^\\s(),|]+(?:\\([^\\s(),|]*\\))*").matches(tag)

/** What precedes a loaded `saffron diff` in the Proposals details pane. */
internal const val DIFF_HEADING = "\n\nWHAT THIS PROPOSAL CHANGES\n\n"

/** The feature edits for the details pane: none when a loaded diff beside them lists them already. */
internal fun editsBeside(edits: List<StatusFeatureEdit>?, diff: String): String = if (diff.startsWith(DIFF_HEADING)) "" else featureEditText(edits)

/** One diff load: its process is started on a pooled thread, and cancelling destroys it whenever it exists. */
internal class DiffLoad {
    @Volatile private var handler: CapturingProcessHandler? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; handler?.destroyProcess() }

    /** [start]'s process, or null when the load was cancelled first: then no process is ever started. */
    fun start(start: () -> CapturingProcessHandler): CapturingProcessHandler? {
        if (cancelled) return null
        val h = start()
        handler = h
        // Cancelled while it started: cancel() saw no process to destroy.
        if (cancelled) h.destroyProcess()
        return h
    }
}

/**
 * The Saffron project an open file is ejected in: its own (nested) project, one with a saffron.config.json or one the
 * tool window discovered, else the one the tool window shows.
 */
internal fun ejectRoot(file: java.nio.file.Path, base: java.nio.file.Path?, shown: java.nio.file.Path, roots: List<java.nio.file.Path>): java.nio.file.Path =
    base?.let { SaffronRoots.rootFor(file, it) }?.takeIf { java.nio.file.Files.exists(it.resolve("saffron.config.json")) || it in roots } ?: shown

/** A path relative to the project inside it: its first segment is not "..", so a folder named "..data" is inside, as in VS Code. */
internal fun inProject(rel: String): Boolean = rel.split('/', '\\').firstOrNull() != ".." && !java.nio.file.Paths.get(rel).isAbsolute
