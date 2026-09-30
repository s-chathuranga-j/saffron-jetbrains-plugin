package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.ui.FEATURE_EXTENSIONS
import ai.saffron.jetbrains.ui.SaffronRoots
import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiElement
import java.nio.file.Files
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
        val (target, root) = target(context) ?: return false
        configuration.command = "run"
        configuration.workingDirectory = root
        configuration.paths = SaffronCommand.joinPaths(relativePaths(root, target))
        configuration.name = "Run ${target.name}"
        return true
    }

    override fun isConfigurationFromContext(configuration: SaffronRunConfiguration, context: ConfigurationContext): Boolean {
        val (target, root) = target(context) ?: return false
        // A blank directory (0.3.2 and earlier) is the project directory.
        val dir = configuration.resolvedWorkingDirectory?.let(FileUtil::toSystemIndependentName)
        return configuration.command == "run" &&
            dir == root &&
            SaffronCommand.splitPaths(configuration.paths) == relativePaths(root, target) &&
            configuration.tags.isBlank()
    }

    /**
     * The file or folder to run and the Saffron project it belongs to; null
     * outside a Saffron project (Cucumber-JVM, SpecFlow and Behat have
     * .feature files too), and for a folder holding other Saffron projects.
     */
    private fun target(context: ConfigurationContext): Pair<VirtualFile, String>? {
        val file = context.location?.virtualFile ?: return null
        if (!file.isDirectory && file.extension !in FEATURE_EXTENSIONS) return null
        val base = context.project.basePath ?: return null
        val roots = SaffronStatusService.getInstance(context.project).roots
        val path = Path.of(file.path)
        // rootFor starts at the parent, so a folder is given as a child of itself.
        val root = SaffronRoots.rootFor(if (file.isDirectory) path.resolve("_") else path, Path.of(base))
        if (!Files.exists(root.resolve("saffron.config.json")) && root !in roots) return null
        if (file.isDirectory && (roots.any { it != root && it.startsWith(path) } || !containsSaffronFiles(file))) return null
        return file to FileUtil.toSystemIndependentName(root.toString())
    }

    /** The target relative to the root; nothing when it IS the root (blank runs every feature file there). */
    private fun relativePaths(root: String, file: VirtualFile): List<String> {
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
