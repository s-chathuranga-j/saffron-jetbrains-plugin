package ai.saffron.jetbrains.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project

/** Runs a Saffron configuration from anywhere in the plugin (tool window buttons). */
object SaffronRunner {

    fun execute(project: Project, name: String, configure: (SaffronRunConfiguration) -> Unit) {
        val runManager = RunManager.getInstance(project)
        val type = SaffronConfigurationType.INSTANCE
        val settings = runManager.findConfigurationByTypeAndName(type, name)
            ?: runManager.createConfiguration(name, type.factory).also {
                it.isTemporary = true
                runManager.addConfiguration(it)
            }
        configure(settings.configuration as SaffronRunConfiguration)
        runManager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }
}
