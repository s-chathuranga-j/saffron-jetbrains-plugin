package ai.saffron.jetbrains

import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files

/**
 * A Saffron project without the npm package installed gets no language
 * server. Say so once per project instead of letting LSP4IJ report a
 * failed launch on every open.
 */
class SaffronStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Runs off the EDT: fills the roots the language server and run menu read.
        val roots = SaffronStatusService.getInstance(project).discoverRoots().filter { Files.exists(it.resolve("saffron.config.json")) }
        if (roots.isEmpty()) return

        // LSP4IJ is an optional dependency: without it the plugin still loads,
        // but there is no completion, go-to-definition or diagnostics. Say so
        // once, because nothing else will.
        if (!lsp4ijAvailable()) {
            hintOnce(
                project,
                LSP_HINT_SHOWN_KEY,
                "Saffron: install LSP4IJ for completion, diagnostics and Run lenses",
                "Highlighting, run configurations and the Saffron tool window work already, and right-click Run " +
                    "inside a scenario runs that scenario alone. Step completion, StepSet go-to-definition, hover, " +
                    "diagnostics and the Run lenses above each scenario come from the language server, which needs " +
                    "the free <b>LSP4IJ</b> plugin (Settings, Plugins, Marketplace). " +
                    "If LSP4IJ is not available for this IDE build yet, they start working once it is.",
            )
            return
        }
        if (roots.none { Files.isDirectory(it.resolve("node_modules/saffron-ai")) }) {
            hintOnce(
                project,
                HINT_SHOWN_KEY,
                "Saffron: install the runner for completion and diagnostics",
                "This project has a saffron.config.json but no saffron-ai package. " +
                    "Run <code>npm i -D saffron-ai</code>. Highlighting works already; " +
                    "the language server starts once the package is present.",
            )
        }
    }

    /**
     * Whether LSP4IJ is installed AND enabled, asked the way that needs no
     * plugin-manager API (those are internal or deprecated from 2026.2): an
     * optional dependency's classes are visible to this plugin's class loader
     * exactly when that dependency is loaded.
     */
    private fun lsp4ijAvailable(): Boolean =
        try {
            Class.forName("com.redhat.devtools.lsp4ij.LanguageServerFactory", false, javaClass.classLoader)
            true
        } catch (_: Throwable) {
            false
        }

    private fun hintOnce(project: Project, key: String, title: String, body: String) {
        val props = PropertiesComponent.getInstance(project)
        if (props.getBoolean(key, false)) return
        props.setValue(key, true)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Saffron")
            .createNotification(title, body, NotificationType.INFORMATION)
            .notify(project)
    }

    private companion object {
        const val HINT_SHOWN_KEY = "ai.saffron.jetbrains.installHintShown"
        const val LSP_HINT_SHOWN_KEY = "ai.saffron.jetbrains.lsp4ijHintShown"
    }
}
