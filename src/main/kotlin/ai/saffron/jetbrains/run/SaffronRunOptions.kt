package ai.saffron.jetbrains.run

import com.intellij.execution.configurations.LocatableRunConfigurationOptions

/** Persisted fields of a Saffron run configuration (stored in the workspace or `.run/`). */
class SaffronRunOptions : LocatableRunConfigurationOptions() {
    var command: String? by string("run")
    var paths: String? by string("")
    var tags: String? by string("")
    var replayOnly: Boolean by property(false)
    var headed: Boolean by property(false)
    var rerecord: Boolean by property(false)
    var extraArgs: String? by string("")
    /** The Saffron project to run in; blank means the IDE project directory. */
    var workingDirectory: String? by string("")
    /** For a `file:LINE` scenario target: the scenario's name (null for a blank one), so the line is found again at launch. */
    var scenario: String? by string()
    /** Its place among the file's scenarios of that name; -1 for any other target. */
    var scenarioIndex: Int by property(-1)
}
