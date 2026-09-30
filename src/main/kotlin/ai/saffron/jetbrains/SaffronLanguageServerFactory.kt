package ai.saffron.jetbrains

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerEnablementSupport
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * The server starts only in a trusted project with a Saffron project under
 * its root: a stray .feature file elsewhere must not run `npx -y`.
 */
class SaffronLanguageServerFactory : LanguageServerFactory, LanguageServerEnablementSupport {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        SaffronLanguageServer(project)

    override fun isEnabled(project: Project): Boolean =
        !PropertiesComponent.getInstance(project).getBoolean(DISABLED_KEY, false) &&
            SaffronCommand.trusted(project) &&
            SaffronStatusService.getInstance(project).roots.isNotEmpty()

    /** The switch in LSP4IJ's settings. */
    override fun setEnabled(enabled: Boolean, project: Project) {
        PropertiesComponent.getInstance(project).setValue(DISABLED_KEY, !enabled)
    }

    private companion object {
        const val DISABLED_KEY = "ai.saffron.jetbrains.lspDisabled"
    }
}
