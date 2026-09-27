package ai.saffron.jetbrains.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/** The form shown for a Saffron configuration in Run/Debug Configurations. */
class SaffronSettingsEditor : SettingsEditor<SaffronRunConfiguration>() {

    private val command = ComboBox(SaffronCommand.COMMANDS.toTypedArray())
    private val paths = JBTextField()
    private val tags = JBTextField()
    private val replayOnly = JBCheckBox("Replay only, no AI (cache misses and failures go red)")
    private val headed = JBCheckBox("Headed browser")
    private val rerecord = JBCheckBox("Re-record the selected scenarios (discards their committed caches)")
    private val extraArgs = RawCommandLineEditor()

    private val pathsLabel = JBLabel()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Command:", command)
        .addLabeledComponent(pathsLabel, paths)
        .addTooltip("Relative to the project root, separated by spaces; put a path that contains a space in double quotes. run: feature files or folders, blank runs everything under features/. accept / reject: proposal files, blank means --all. trace: one scenario name, or feature:scenario, quotes optional; blank opens every traced scenario.")
        .addLabeledComponent("Tags:", tags)
        .addTooltip("Only scenarios carrying any of these @tags, comma separated (for example @smoke,@checkout).")
        .addComponent(replayOnly)
        .addComponent(headed)
        .addComponent(rerecord)
        .addLabeledComponent("Extra arguments:", extraArgs)
        .addTooltip("Anything else saffron accepts, for example --strict or --workers 4.")
        .panel

    init {
        command.addActionListener { updateEnabled() }
        updateEnabled()
    }

    private fun updateEnabled() {
        val selected = command.selectedItem
        // One field, named for what the chosen command reads from it.
        pathsLabel.text = when (selected) {
            "trace" -> "Scenario:"
            "accept", "reject" -> "Proposal files:"
            else -> "Files or folders:"
        }
        paths.isEnabled = selected != "report" && selected != "prune"
        for (c in listOf(tags, replayOnly, headed, rerecord)) c.isEnabled = selected == "run"
    }

    override fun resetEditorFrom(c: SaffronRunConfiguration) {
        command.selectedItem = c.command
        paths.text = c.paths
        tags.text = c.tags
        replayOnly.isSelected = c.replayOnly
        headed.isSelected = c.headed
        rerecord.isSelected = c.rerecord
        extraArgs.text = c.extraArgs
        updateEnabled()
    }

    override fun applyEditorTo(c: SaffronRunConfiguration) {
        c.command = command.selectedItem as String
        c.paths = paths.text.trim()
        c.tags = tags.text.trim()
        c.replayOnly = replayOnly.isSelected
        c.headed = headed.isSelected
        c.rerecord = rerecord.isSelected
        c.extraArgs = extraArgs.text.trim()
    }

    override fun createEditor(): JComponent = panel
}
