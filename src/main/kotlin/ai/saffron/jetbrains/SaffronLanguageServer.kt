package ai.saffron.jetbrains

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider

/**
 * Launches `saffron lsp` in the IDE root, which finds nested projects
 * itself: the binary of the first discovered project that has one, then
 * the IDE root's, otherwise `npx`.
 */
class SaffronLanguageServer(project: Project) : OSProcessStreamConnectionProvider() {
    init {
        val roots = SaffronStatusService.getInstance(project).roots.map { it.toString() } + listOfNotNull(project.basePath)
        val binary = roots.firstNotNullOfOrNull(SaffronCommand::localBinary)
        commandLine = SaffronCommand.base(project.basePath, binary).withParameters("lsp")
    }
}
