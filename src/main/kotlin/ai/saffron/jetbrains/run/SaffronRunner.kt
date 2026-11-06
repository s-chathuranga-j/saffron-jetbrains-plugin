package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/** Runs a Saffron configuration from anywhere in the plugin (tool window buttons). */
object SaffronRunner {

    /**
     * The name [prepare] gives [name], past any saved configuration of it
     * ("Saffron: <name>"), and the temporary configuration there it would reuse.
     */
    fun slot(project: Project, name: String): Pair<String, RunnerAndConfigurationSettings?> {
        val runManager = RunManager.getInstance(project)
        var label = name
        var found = runManager.findConfigurationByTypeAndName(SaffronConfigurationType.INSTANCE, label)
        while (found != null && !found.isTemporary) {
            label = "Saffron: $label"
            found = runManager.findConfigurationByTypeAndName(SaffronConfigurationType.INSTANCE, label)
        }
        return label to found
    }

    /** Asked before `saffron accept --all`, as the VS Code extension asks: it accepts what is pending when it runs, unread. */
    fun confirmAcceptAll(project: Project): Boolean = Messages.showYesNoDialog(
        project,
        "Accept every pending proposal that saffron accept --all takes? It accepts what is pending when it runs, including proposals you have not reviewed.",
        "Accept All Proposals",
        "Accept All",
        "Cancel",
        Messages.getWarningIcon(),
    ) == Messages.YES

    fun execute(project: Project, name: String, configure: (SaffronRunConfiguration) -> Unit) {
        ProgramRunnerUtil.executeConfiguration(prepare(project, name, configure), DefaultRunExecutor.getRunExecutorInstance())
    }

    /**
     * The named configuration, reset and then configured. It is reused by
     * name, so without the reset a switch set by an earlier button (rerecord,
     * extra arguments) would carry into this one unseen. Only a temporary one
     * is reused: a saved configuration of that name is the user's and is
     * never reset, so the button gets its own under "Saffron: <name>".
     */
    fun prepare(project: Project, name: String, configure: (SaffronRunConfiguration) -> Unit): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = SaffronConfigurationType.INSTANCE
        val (label, found) = slot(project, name)
        val settings = found
            ?: runManager.createConfiguration(label, type.factory).also {
                it.isTemporary = true
                runManager.addConfiguration(it)
            }
        val c = settings.configuration as SaffronRunConfiguration
        c.command = "run"
        c.paths = ""
        c.tags = ""
        c.replayOnly = false
        c.headed = false
        c.rerecord = false
        c.extraArgs = ""
        c.workingDirectory = SaffronStatusService.getInstance(project).root.toString()
        configure(c)
        // An accept, reject, export or eject is never the selected configuration: the Run button would repeat it on
        // whatever is pending then, push a branch again, or write specs again.
        // A prune too: after its confirmation it carries --yes, and Run would delete what is orphaned then, unasked (found in review).
        if (c.command !in setOf("accept", "reject", "export", "eject", "prune")) runManager.selectedConfiguration = settings
        return settings
    }
}
