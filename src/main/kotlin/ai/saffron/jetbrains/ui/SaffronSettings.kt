package ai.saffron.jetbrains.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBIntSpinner
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** Settings → Tools → Saffron: the CI results download limit, the same meaning as VS Code's saffron.ciMaxDownloadMB. */
class SaffronSettings : Configurable {

    private val limit = JBIntSpinner(ciMaxDownloadMb(), 0, 1_000_000)

    override fun getDisplayName() = "Saffron"

    override fun createComponent(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("CI results download limit (MB):", limit)
            .addTooltip("CI Runs asks before downloading a run's results above this size. 0 downloads any size without asking.")
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel

    override fun isModified() = limit.number != ciMaxDownloadMb()

    override fun apply() = PropertiesComponent.getInstance().setValue(CI_MAX_DOWNLOAD_KEY, limit.number, CI_MAX_DOWNLOAD_DEFAULT)

    override fun reset() {
        limit.number = ciMaxDownloadMb()
    }
}
