package ai.saffron.jetbrains.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment

class SaffronCommandLineState(
    private val configuration: SaffronRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(SaffronCommand.forConfiguration(configuration))
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
