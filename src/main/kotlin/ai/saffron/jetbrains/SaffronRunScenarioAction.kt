package ai.saffron.jetbrains

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronScenarioRun
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.io.FileUtil
import com.redhat.devtools.lsp4ij.commands.CommandExecutor
import com.redhat.devtools.lsp4ij.commands.LSPCommand
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction
import java.io.File

/** The argument `saffron lsp` puts on its Run lenses. */
class RunScenarioArgument(
    /** The project root, where the run starts. */
    val root: String = "",
    /** `features/login.saffron:Successful login` (or `:12` for a scenario with no usable name), relative to the root. */
    val target: String = "",
    val scenario: String = "",
    /** "run", "replay" or "headed". */
    val mode: String = "run",
)

/**
 * A Run lens from `saffron lsp` (Run, Replay only, Headed above a Scenario).
 * LSP4IJ runs the IDE action named after a lens's command when the server
 * does not run it itself, so this is `saffron.runScenario`: it saves the
 * files, makes or reuses the scenario's run configuration and runs it.
 */
class SaffronRunScenarioAction : LSPCommandAction(), DumbAware {

    override fun commandPerformed(command: LSPCommand, e: AnActionEvent) {
        val project = e.project ?: return
        val argument = command.getArgumentAt(0, RunScenarioArgument::class.java) ?: return
        if (argument.target.isBlank() || !File(argument.root).isDirectory) return
        // Only inside the project the user trusted: never a folder the server placed elsewhere.
        val base = project.basePath ?: return
        if (!FileUtil.isAncestor(base, argument.root, false)) return
        if (!SaffronCommand.trusted(project)) {
            NotificationGroupManager.getInstance().getNotificationGroup("Saffron")
                .createNotification("Trust the project to run Saffron", NotificationType.WARNING)
                .notify(project)
            return
        }
        // The language server can be another project's saffron-ai than the one this runs.
        if (!SaffronScenarioRun.runnerRunsScenarios(argument.root)) {
            NotificationGroupManager.getInstance().getNotificationGroup("Saffron")
                .createNotification(
                    "Running one scenario needs saffron-ai 0.9.8 or later",
                    "Run npm i -D saffron-ai@latest in ${argument.root}.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }
        // The runner reads the file from disk.
        FileDocumentManager.getInstance().saveAllDocuments()
        val settings = SaffronScenarioRun.configuration(project, argument.root, argument.target, argument.scenario, argument.mode)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    // Saving and starting a run belong on the UI thread.
    override fun getCommandPerformedThread(): ActionUpdateThread = ActionUpdateThread.EDT

    // Only a lens carries a command: not offered in Find Action, where it would do nothing.
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommandExecutor.LSP_COMMAND) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
