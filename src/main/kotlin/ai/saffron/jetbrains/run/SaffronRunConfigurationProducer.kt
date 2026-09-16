package ai.saffron.jetbrains.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiElement

/**
 * Right-click a `.saffron` file (project view or editor) or a folder holding
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
        configuration.command = "run"
        configuration.paths = relativePath(context, target)
        configuration.name = "Run ${target.name}"
        return true
    }

    override fun isConfigurationFromContext(configuration: SaffronRunConfiguration, context: ConfigurationContext): Boolean {
        val target = target(context) ?: return false
        return configuration.command == "run" &&
            configuration.paths == relativePath(context, target) &&
            configuration.tags.isBlank()
    }

    private fun target(context: ConfigurationContext): VirtualFile? {
        val file = context.location?.virtualFile ?: return null
        val matches = if (file.isDirectory) containsSaffronFiles(file) else file.extension == "saffron"
        return if (matches) file else null
    }

    private fun relativePath(context: ConfigurationContext, file: VirtualFile): String {
        val base = context.project.basePath ?: return file.path
        val baseDir = LocalFileSystem.getInstance().findFileByPath(base) ?: return file.path
        return VfsUtilCore.getRelativePath(file, baseDir) ?: file.path
    }

    companion object {
        private val SKIP = setOf("node_modules", ".git", ".saffron", "dist", "build", "target")

        fun containsSaffronFiles(dir: VirtualFile): Boolean {
            var found = false
            VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Any>(limit(12)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (found) return false
                    if (file.isDirectory) return file.name !in SKIP
                    if (file.extension == "saffron") found = true
                    return !found
                }
            })
            return found
        }
    }
}
