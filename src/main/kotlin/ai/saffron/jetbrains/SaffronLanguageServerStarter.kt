package ai.saffron.jetbrains

import ai.saffron.jetbrains.ui.RootsListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import java.nio.file.Path

/**
 * Starts the language server for the files already open once a Saffron project appears in the open project
 * (SAF-207): LSP4IJ asks [SaffronLanguageServerFactory.isEnabled] as files open, and it said no for the ones open
 * before. Registered in saffron-lsp4ij.xml, so it loads only with LSP4IJ. A server switched off, or an untrusted
 * project, stays off: LSP4IJ asks isEnabled again and refuses.
 */
class SaffronLanguageServerStarter(private val project: Project) : RootsListener {
    override fun rootsChanged(roots: List<Path>) {
        if (roots.isEmpty() || !SaffronLanguageServerFactory().isEnabled(project)) return
        try {
            LanguageServerManager.getInstance(project).start(SERVER_ID)
        } catch (e: Exception) {
            Logger.getInstance(SaffronLanguageServerStarter::class.java).info("Saffron language server not started: ${e.message}")
        }
    }

    internal companion object {
        /** The server's id in saffron-lsp4ij.xml. */
        const val SERVER_ID = "saffron"
    }
}
