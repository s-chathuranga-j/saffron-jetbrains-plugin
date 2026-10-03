package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project

/** Runs a Saffron configuration from anywhere in the plugin (tool window buttons). */
object SaffronRunner {

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
        var label = name
        var found = runManager.findConfigurationByTypeAndName(type, label)
        while (found != null && !found.isTemporary) {
            label = "Saffron: $label"
            found = runManager.findConfigurationByTypeAndName(type, label)
        }
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
        runManager.selectedConfiguration = settings
        return settings
    }
}
