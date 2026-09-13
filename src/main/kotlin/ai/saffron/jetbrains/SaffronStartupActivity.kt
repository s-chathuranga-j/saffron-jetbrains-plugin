package ai.saffron.jetbrains

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Saffron project without the npm package installed gets no language
 * server — say so once, instead of letting LSP4IJ report a failed launch.
 */
class SaffronStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val base = project.basePath?.let(Path::of) ?: return
        val isSaffronProject = Files.exists(base.resolve("saffron.config.json"))
        val packageInstalled = Files.isDirectory(base.resolve("node_modules/saffron-ai"))
        if (!isSaffronProject || packageInstalled) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Saffron")
            .createNotification(
                "Saffron: install the runner for completion and diagnostics",
                "This project has a saffron.config.json but no saffron-ai package. " +
                    "Run <code>npm i -D saffron-ai</code> — highlighting works already; " +
                    "the language server starts once the package is present.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
