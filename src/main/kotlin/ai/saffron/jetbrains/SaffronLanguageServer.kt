package ai.saffron.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * Launches `saffron lsp` for the project — the project-local binary when
 * `saffron-ai` is installed there (fast, exact version), otherwise `npx`.
 */
class SaffronLanguageServer(project: Project) : OSProcessStreamConnectionProvider() {

    init {
        val basePath = project.basePath
        val local = basePath?.let { Path.of(it, "node_modules", ".bin", if (SystemInfo.isWindows) "saffron.cmd" else "saffron") }
        val command = if (local != null && Files.isExecutable(local)) {
            GeneralCommandLine(local.toString(), "lsp")
        } else {
            // "saffron" alone would resolve an unrelated npm package; the binary
            // lives in saffron-ai, so pin the package explicitly.
            GeneralCommandLine(if (SystemInfo.isWindows) "npx.cmd" else "npx", "-y", "-p", "saffron-ai", "saffron", "lsp")
        }
        if (basePath != null) command.withWorkDirectory(basePath)
        commandLine = command
    }
}
