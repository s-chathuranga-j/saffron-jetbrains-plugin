package ai.saffron.jetbrains

import ai.saffron.jetbrains.run.SaffronCommand
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider

/**
 * Launches `saffron lsp` for the project: the project-local binary when
 * `saffron-ai` is installed there (fast, exact version), otherwise `npx`.
 */
class SaffronLanguageServer(project: Project) : OSProcessStreamConnectionProvider() {
    init {
        commandLine = SaffronCommand.base(project.basePath).withParameters("lsp")
    }
}
