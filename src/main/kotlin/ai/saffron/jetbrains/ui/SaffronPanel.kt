package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronRunner
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Feature files with checkboxes, a search field, replay-only / headed
 * switches, and the latest run's totals. Every button runs a Saffron run
 * configuration, so output lands in the Run tool window and the
 * configuration stays in the run widget for re-runs.
 */
class SaffronPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {

    private val list = CheckBoxList<SaffronFile>()
    private val search = SearchTextField(false)
    private val replayOnly = JBCheckBox("Replay only (no AI)")
    private val headed = JBCheckBox("Headed browser")
    private val status = JBLabel()
    private val pending = JBLabel()
    private val hint = JBLabel()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private var all: List<SaffronFile> = emptyList()
    private var state: SaffronProjectState? = null

    init {
        toolbar = buildToolbar()
        setContent(buildContent())
        list.setEmptyText("No .saffron files found under the project")
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val index = list.locationToIndex(e.point)
                if (index >= 0) list.getItemAt(index)?.let(::open)
            }
        })
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        val connection = project.messageBus.connect(parent)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(".saffron") || it.path.contains("/.saffron/") }) scheduleRefresh()
            }
        })
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) =
                scheduleRefresh()
        })
        refresh()
    }

    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup(
            action("Run All", "saffron run over every feature file", AllIcons.Actions.RunAll) { runAll() },
            action("Run Selected", "saffron run over the checked files", AllIcons.Actions.Execute) { runSelected() },
            action("Open Report", "Open the latest HTML report in the browser", AllIcons.Actions.Preview) { openReport() },
            action("Accept All Proposals", "saffron accept --all: promote pending proposals to committed caches", AllIcons.Actions.Commit) { acceptAll() },
            action("Refresh", "Rescan feature files and the latest report", AllIcons.Actions.Refresh) { refresh() },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("SaffronToolWindow", group, true)
        toolbar.targetComponent = this
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.CENTER) }
    }

    private fun action(text: String, description: String, icon: javax.swing.Icon, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun buildContent(): JPanel {
        val options = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 6)
            add(replayOnly)
            add(javax.swing.Box.createHorizontalStrut(12))
            add(headed)
        }
        val top = JPanel(BorderLayout()).apply {
            add(options, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(0, 6, 4, 6); add(search, BorderLayout.CENTER) }, BorderLayout.SOUTH)
        }
        val bottom = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6)
            add(status)
            add(pending)
            add(hint)
        }
        for (label in listOf(status, pending, hint)) label.foreground = UIUtil.getContextHelpForeground()
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun runAll() = SaffronRunner.execute(project, "Saffron: run all") {
        it.command = "run"; it.paths = ""; it.tags = ""; it.replayOnly = replayOnly.isSelected; it.headed = headed.isSelected
    }

    private fun runSelected() {
        val selected = all.filter { list.isItemSelected(it) }
        if (selected.isEmpty()) {
            hint.text = "Tick one or more files first, or use Run All."
            return
        }
        val paths = selected.joinToString(" ") { it.relativePath }
        val name = if (selected.size == 1) "Run ${selected[0].relativePath.substringAfterLast('/')}" else "Saffron: run ${selected.size} files"
        SaffronRunner.execute(project, name) {
            it.command = "run"; it.paths = paths; it.tags = ""; it.replayOnly = replayOnly.isSelected; it.headed = headed.isSelected
        }
    }

    private fun openReport() {
        val base = project.basePath ?: return
        val html = java.nio.file.Path.of(base, ".saffron", "reports", "latest.html")
        if (java.nio.file.Files.exists(html)) BrowserUtil.browse(html.toFile()) else hint.text = "No report yet: run something first."
    }

    private fun acceptAll() = SaffronRunner.execute(project, "Saffron: accept all") { it.command = "accept" }

    private fun open(file: SaffronFile) {
        val base = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/${file.relativePath}") ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    // ---------------------------------------------------------------- state

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refresh() }, 400)
    }

    private fun refresh() {
        val base = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = runCatching { SaffronProjectScan.scan(base) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ show(scanned) }, project.disposed)
        }
    }

    private fun show(s: SaffronProjectState) {
        state = s
        val checked = all.filter { list.isItemSelected(it) }.map { it.relativePath }.toSet()
        all = s.files
        applyFilter(checked)
        status.text = s.lastRun?.let { r ->
            "Last run: ${r.green} passed · ${r.yellow} pending review · ${r.red} failed · $${"%.2f".format(r.costUsd)}${ago(r.finishedAt)}"
        } ?: "No runs yet."
        pending.text = when (s.pendingProposals) {
            0 -> "No proposals pending review."
            1 -> "1 proposal pending review (Accept All Proposals, or saffron accept)."
            else -> "${s.pendingProposals} proposals pending review (Accept All Proposals, or saffron accept)."
        }
        hint.text = if (s.packageInstalled) "" else "saffron-ai is not installed here: runs fall back to npx. Run npm i -D saffron-ai."
    }

    private fun applyFilter(keepChecked: Set<String> = all.filter { list.isItemSelected(it) }.map { it.relativePath }.toSet()) {
        val q = search.text.trim().lowercase()
        list.clear()
        for (f in all) {
            if (q.isNotEmpty() && !f.relativePath.lowercase().contains(q)) continue
            val label = when {
                f.isLibrary -> "${f.relativePath}   (step sets only)"
                f.scenarios == 1 -> "${f.relativePath}   1 scenario"
                else -> "${f.relativePath}   ${f.scenarios} scenarios"
            }
            list.addItem(f, label, f.relativePath in keepChecked)
        }
    }

    private fun ago(at: Instant?): String {
        at ?: return ""
        val d = Duration.between(at, Instant.now())
        val text = when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} d ago"
        }
        return " · $text"
    }
}
