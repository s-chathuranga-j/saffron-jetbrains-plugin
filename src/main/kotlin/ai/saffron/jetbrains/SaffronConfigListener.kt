package ai.saffron.jetbrains

import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
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
        if (touchesConfig(base, events)) SaffronStatusService.getInstance(project).rootsMayHaveChanged()
    }

    internal companion object {
        private const val CONFIG = "saffron.config.json"

        /** Whether [events] add, move, rename or remove a saffron.config.json under [base] (where discovery looks). */
        fun touchesConfig(base: String, events: List<VFileEvent>): Boolean {
            val root = FileUtil.toSystemIndependentName(base).trimEnd('/') + "/"
            return events.any { e ->
                val paths = listOfNotNull(
                    e.path,
                    (e as? VFileMoveEvent)?.newPath,
                    (e as? VFilePropertyChangeEvent)?.takeIf { it.isRename }?.newPath,
                    (e as? VFileCopyEvent)?.let { "${it.newParent.path}/${it.newChildName}" },
                )
                // A folder created or moved in may hold one: the VFS reports the folder alone.
                val folder = (e is VFileCreateEvent && e.isDirectory) || ((e is VFileMoveEvent || e is VFileCopyEvent) && e.file?.isDirectory == true)
                paths.any { p -> p.startsWith(root) && (p.endsWith("/$CONFIG") || folder) }
            }
        }
    }
}
