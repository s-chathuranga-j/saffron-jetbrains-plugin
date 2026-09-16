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
}
