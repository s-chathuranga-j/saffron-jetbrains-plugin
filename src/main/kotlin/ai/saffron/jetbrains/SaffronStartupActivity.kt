package ai.saffron.jetbrains

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Saffron project without the npm package installed gets no language
 * server. Say so once per project instead of letting LSP4IJ report a
 * failed launch on every open.
 */
class SaffronStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val base = project.basePath?.let(Path::of) ?: return
        val isSaffronProject = Files.exists(base.resolve("saffron.config.json"))
        val packageInstalled = Files.isDirectory(base.resolve("node_modules/saffron-ai"))
        if (!isSaffronProject || packageInstalled) return
        val props = PropertiesComponent.getInstance(project)
        if (props.getBoolean(HINT_SHOWN_KEY, false)) return
        props.setValue(HINT_SHOWN_KEY, true)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Saffron")
            .createNotification(
                "Saffron: install the runner for completion and diagnostics",
                "This project has a saffron.config.json but no saffron-ai package. " +
                    "Run <code>npm i -D saffron-ai</code>. Highlighting works already; " +
                    "the language server starts once the package is present.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    private companion object {
        const val HINT_SHOWN_KEY = "ai.saffron.jetbrains.installHintShown"
    }
}
