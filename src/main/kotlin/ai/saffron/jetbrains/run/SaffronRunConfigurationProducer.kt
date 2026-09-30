package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.ui.FEATURE_EXTENSIONS
import ai.saffron.jetbrains.ui.SaffronRoots
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiElement
import java.nio.file.Path

/**
 * Right-click a `.saffron` or `.feature` file (project view or editor) or a folder holding
 * them and the context menu offers "Run 'login.saffron'": a run
 * configuration is created for that target, the way JUnit and Cypress do it.
 */
class SaffronRunConfigurationProducer : LazyRunConfigurationProducer<SaffronRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory = SaffronConfigurationType.INSTANCE.factory

    override fun setupConfigurationFromContext(
        configuration: SaffronRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = target(context) ?: return false
        val root = rootOf(context, target)
        configuration.command = "run"
        configuration.workingDirectory = root
        configuration.paths = SaffronCommand.joinPaths(relativePaths(root, target))
        configuration.name = "Run ${target.name}"
        return true
    }

    override fun isConfigurationFromContext(configuration: SaffronRunConfiguration, context: ConfigurationContext): Boolean {
        val target = target(context) ?: return false
        val root = rootOf(context, target)
        return configuration.command == "run" &&
            configuration.workingDirectory == root &&
            SaffronCommand.splitPaths(configuration.paths) == relativePaths(root, target) &&
            configuration.tags.isBlank()
    }

    private fun target(context: ConfigurationContext): VirtualFile? {
        val file = context.location?.virtualFile ?: return null
        val matches = if (file.isDirectory) containsSaffronFiles(file) else file.extension in FEATURE_EXTENSIONS
        return if (matches) file else null
    }

    /** The nearest folder above the target holding saffron.config.json, never above the project; else the project. */
    private fun rootOf(context: ConfigurationContext, target: VirtualFile): String? {
        val base = context.project.basePath ?: return null
        // rootFor starts at the parent, so a folder is given as a child of itself.
        val start = Path.of(target.path).let { if (target.isDirectory) it.resolve("_") else it }
        return FileUtil.toSystemIndependentName(SaffronRoots.rootFor(start, Path.of(base)).toString())
    }

    /** The target relative to the root; nothing when it IS the root (blank runs every feature file there). */
    private fun relativePaths(root: String?, file: VirtualFile): List<String> {
        root ?: return listOf(file.path)
        val rel = FileUtil.getRelativePath(root, file.path, '/')
        return when {
            rel == null || rel.startsWith("..") -> listOf(file.path)
            rel == "." -> emptyList()
            else -> listOf(rel)
        }
    }

    companion object {
        private val SKIP = setOf("node_modules", ".git", ".saffron", "dist", "build", "target")

        fun containsSaffronFiles(dir: VirtualFile): Boolean {
            var found = false
            VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Any>(limit(12)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (found) return false
                    if (file.isDirectory) return file.name !in SKIP
                    if (file.extension in FEATURE_EXTENSIONS) found = true
                    return !found
                }
            })
            return found
        }
    }
}
