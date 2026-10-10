package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.ui.FEATURE_EXTENSIONS
import ai.saffron.jetbrains.ui.SaffronProjectScan
import ai.saffron.jetbrains.ui.SaffronRoots
import ai.saffron.jetbrains.ui.SaffronStatusService
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
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
 * In the editor with the caret in a scenario it is "Run 'Successful login'",
 * that scenario alone, as a Run lens runs it (no LSP4IJ needed).
 */
class SaffronRunConfigurationProducer : LazyRunConfigurationProducer<SaffronRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory = SaffronConfigurationType.INSTANCE.factory

    override fun setupConfigurationFromContext(
        configuration: SaffronRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val (target, root) = target(context) ?: return false
        val scenario = scenarioAtCaret(context, root, target)
        configuration.command = "run"
        configuration.workingDirectory = root
        configuration.paths = SaffronCommand.joinPaths(paths(root, target, scenario))
        configuration.name = scenario?.let { SaffronScenarioRun.name(it.first, "run") } ?: "Run ${target.name}"
        return true
    }

    override fun isConfigurationFromContext(configuration: SaffronRunConfiguration, context: ConfigurationContext): Boolean {
        val (target, root) = target(context) ?: return false
        // A blank directory (0.3.2 and earlier) is the project directory.
        val dir = configuration.resolvedWorkingDirectory?.let(FileUtil::toSystemIndependentName)
        return configuration.command == "run" &&
            dir == root &&
            SaffronCommand.splitPaths(configuration.paths) == paths(root, target, scenarioAtCaret(context, root, target)) &&
            configuration.tags.isBlank() &&
            // Right-click Run is a plain run: not a lens's Replay only or Headed configuration, nor a re-record.
            !configuration.replayOnly && !configuration.headed && !configuration.rerecord
    }

    /**
     * The file or folder to run and the Saffron project it belongs to; null
     * outside a Saffron project or its test folder (Cucumber-JVM, SpecFlow and Behat
     * have .feature files too), and for a folder holding other Saffron projects.
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
        // The project itself, or what is in its test folder: Cucumber files elsewhere are not Saffron's.
        if (path.normalize() != root.toAbsolutePath().normalize() && !SaffronProjectScan.inFeaturesDir(root, path)) return null
        if (file.isDirectory && (roots.any { it != root && it.startsWith(path) } || !containsSaffronFiles(file))) return null
        return file to FileUtil.toSystemIndependentName(root.toString())
    }

    /** The target's paths, or the one scenario's `file:name`. */
    private fun paths(root: String, target: VirtualFile, scenario: Pair<String, Int>?): List<String> =
        scenario?.let { (name, line) -> relativePaths(root, target).map { SaffronScenarioRun.target(it, name, line) } } ?: relativePaths(root, target)

    /** The scenario at the editor's caret, when the context is that file's editor and the runner takes scenario targets. */
    private fun scenarioAtCaret(context: ConfigurationContext, root: String, target: VirtualFile): Pair<String, Int>? {
        if (target.isDirectory) return null
        val editor = CommonDataKeys.EDITOR.getData(context.dataContext) ?: return null
        if (FileDocumentManager.getInstance().getFile(editor.document) != target) return null
        val scenario = SaffronScenarioRun.scenarioAt(editor.document.immutableCharSequence, editor.caretModel.logicalPosition.line) ?: return null
        return scenario.takeIf { SaffronScenarioRun.runnerRunsScenarios(root) }
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
