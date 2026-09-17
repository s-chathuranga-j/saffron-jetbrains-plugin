package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.SaffronIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue

/** The "Saffron" entry in Run/Debug Configurations → Add New Configuration. */
class SaffronConfigurationType : ConfigurationTypeBase(
    ID,
    "Saffron",
    "Run Saffron scenarios, open the latest report, accept or reject pending proposals",
    NotNullLazyValue.createValue { SaffronIcons.FILE },
) {
    init {
        addFactory(SaffronConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories[0]

    companion object {
        const val ID = "SaffronRunConfiguration"
        val INSTANCE: SaffronConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(SaffronConfigurationType::class.java)
    }
}

class SaffronConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "Saffron"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        SaffronRunConfiguration(project, this, "Saffron")

    override fun getOptionsClass(): Class<out BaseState> = SaffronRunOptions::class.java
}
