package ai.saffron.jetbrains.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

/**
 * A Saffron run configuration: `saffron run` over files, folders and tags
 * (with replay-only, headed and re-record switches), `saffron report`, or
 * `saffron accept --all`. Output goes to the Run tool window.
 */
class SaffronRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<SaffronRunOptions>(project, factory, name) {

    public override fun getOptions(): SaffronRunOptions = super.getOptions() as SaffronRunOptions

    var command: String
        get() = options.command?.takeIf { it.isNotBlank() } ?: "run"
        set(value) { options.command = value }

    var paths: String
        get() = options.paths ?: ""
        set(value) { options.paths = value }

    var tags: String
        get() = options.tags ?: ""
        set(value) { options.tags = value }

    var replayOnly: Boolean
        get() = options.replayOnly
        set(value) { options.replayOnly = value }

    var headed: Boolean
        get() = options.headed
        set(value) { options.headed = value }

    var rerecord: Boolean
        get() = options.rerecord
        set(value) { options.rerecord = value }

    var extraArgs: String
        get() = options.extraArgs ?: ""
        set(value) { options.extraArgs = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SaffronSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        SaffronCommandLineState(this, environment)

    override fun checkConfiguration() {
        if (command !in SaffronCommand.COMMANDS) {
            throw RuntimeConfigurationError("Unknown Saffron command \"$command\"; use run, report, accept or reject")
        }
        if (project.basePath == null) throw RuntimeConfigurationError("The project has no directory to run in")
    }

    override fun suggestedName(): String = SaffronCommand.describe(this)
}
