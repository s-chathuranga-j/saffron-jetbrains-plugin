package ai.saffron.jetbrains

import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * The tool window and the language server are decided once, at project open. A saffron.config.json that appears
 * afterwards (`saffron init` in the open project, a checkout) finds the Saffron projects again, so both follow without
 * reopening it (SAF-207). Registered for every project in plugin.xml; the walk itself runs off the EDT.
 */
class SaffronConfigListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val base = project.basePath ?: return
        val service = SaffronStatusService.getInstance(project)
        if (touchesConfig(base, events, service.roots.map { FileUtil.toSystemIndependentName(it.toString()) })) service.rootsMayHaveChanged()
    }

    internal companion object {
        private const val CONFIG = "saffron.config.json"

        /** Folders no Saffron project is looked for in: installs, build output, version control. */
        private val IGNORED = setOf("node_modules", "build", "out", ".gradle", ".git", "dist", "target")

        /**
         * Whether [events] add, move, rename or remove a saffron.config.json under [base] (where discovery looks), never
         * a save of one (its content changes nothing discovery reads). While no Saffron project is known ([roots] empty),
         * a folder created, moved or copied in too, which may hold one; once one is, a folder removed or moved that holds
         * a root. Never inside node_modules, build output or .git.
         */
        fun touchesConfig(base: String, events: List<VFileEvent>, roots: List<String>): Boolean {
            val top = FileUtil.toSystemIndependentName(base).trimEnd('/') + "/"
            fun watched(p: String) = p.startsWith(top) && p.removePrefix(top).split('/').dropLast(1).none { it in IGNORED }
            return events.any { e ->
                if (e is VFileContentChangeEvent) return@any false
                val paths = listOfNotNull(
                    e.path,
                    (e as? VFileMoveEvent)?.newPath,
                    (e as? VFilePropertyChangeEvent)?.takeIf { it.isRename }?.newPath,
                    (e as? VFileCopyEvent)?.let { "${it.newParent.path}/${it.newChildName}" },
                ).filter(::watched)
                if (paths.isEmpty() || (e is VFilePropertyChangeEvent && !e.isRename)) return@any false
                val directory = (e is VFileCreateEvent && e.isDirectory) || e.file?.isDirectory == true
                when {
                    paths.any { it.endsWith("/$CONFIG") } -> true
                    !directory -> false
                    roots.isEmpty() -> (e is VFileCreateEvent || e is VFileMoveEvent || e is VFileCopyEvent) && paths.none { it.substringAfterLast('/') in IGNORED }
                    else -> (e is VFileDeleteEvent || e is VFileMoveEvent || e is VFilePropertyChangeEvent) && roots.any { r -> r == e.path || r.startsWith(e.path.trimEnd('/') + "/") }
                }
            }
        }
    }
}
