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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import java.nio.file.Files
import java.nio.file.Path

/**
 * Right-click a `.saffron` or `.feature` file (project view or editor) or a folder holding
 * them and the context menu offers "Run 'login.saffron'": a run
 * configuration is created for that target, the way JUnit does it.
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
        // A plain run: none of the template's switches or arguments carry in, as SaffronRunner.prepare resets a lens's.
        configuration.tags = ""
        configuration.replayOnly = false
        configuration.headed = false
        configuration.rerecord = false
        configuration.extraArgs = ""
        // A by-line target is found again from its scenario's name and place among that name's at launch.
        configuration.scenario = scenario?.takeIf { it.byLine }?.name
        configuration.scenarioIndex = scenario?.takeIf { it.byLine }?.index ?: -1
        configuration.name = scenario?.let { SaffronScenarioRun.name(it.name, "run") } ?: "Run ${target.name}"
        return true
    }

    override fun isConfigurationFromContext(configuration: SaffronRunConfiguration, context: ConfigurationContext): Boolean {
        val (target, root) = target(context) ?: return false
        // A blank directory (0.3.2 and earlier) is the project directory.
        val dir = configuration.resolvedWorkingDirectory?.let(FileUtil::toSystemIndependentName)
        val scenario = scenarioAtCaret(context, root, target)
        val samePaths = if (configuration.scenarioIndex >= 0 || scenario?.byLine == true) {
            // By line: the same file and the same scenario (name and place), never a line another scenario now holds.
            configuration.scenario.orEmpty() == scenario?.name && configuration.scenarioIndex == scenario?.index &&
                SaffronCommand.splitPaths(configuration.paths).map(SaffronScenarioRun::fileOf) == relativePaths(root, target)
        } else {
            SaffronCommand.splitPaths(configuration.paths) == paths(root, target, scenario)
        }
        return configuration.command == "run" &&
            dir == root &&
            samePaths &&
            configuration.tags.isBlank() &&
            // Right-click Run is a plain run: not a lens's Replay only or Headed configuration, a re-record, or one with arguments of its own.
            !configuration.replayOnly && !configuration.headed && !configuration.rerecord && configuration.extraArgs.isBlank()
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
        if (file.isDirectory && (roots.any { it != root && it.startsWith(path) } || !containsSaffronFiles(walked(file, path, root)))) return null
        return file to FileUtil.toSystemIndependentName(root.toString())
    }

    /**
     * The folder the runner walks for [dir]: the project root runs its test folder, so that
     * folder is looked in, never the whole root with its build, target and .gradle folders.
     */
    private fun walked(dir: VirtualFile, path: Path, root: Path): VirtualFile? {
        if (path.normalize() != root.toAbsolutePath().normalize()) return dir
        val features = SaffronProjectScan.featuresDir(root)
        // Where a linked test folder really is: its files are walked, as the runner walks them.
        val real = runCatching { features.toRealPath() }.getOrNull() ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(real)
    }

    /** The target's paths, or the one scenario's `file:name` (`file:line` for a name the file has twice). */
    private fun paths(root: String, target: VirtualFile, scenario: Caret?): List<String> =
        scenario?.let { s -> relativePaths(root, target).map { SaffronScenarioRun.target(it, s.name, s.line, s.twice) } } ?: relativePaths(root, target)

    /** The scenario at the caret: its name, 1-based line, whether another has its name, and its place among those of its name. */
    private class Caret(val name: String, val line: Int, val twice: Boolean, val index: Int) {
        /** Run by its line: the runner would read the name as lines, or take the first of its name. */
        val byLine: Boolean get() = SaffronScenarioRun.byLine(name, twice)
    }

    /**
     * The scenario at the editor's caret, when the context is that file's editor and the runner takes scenario targets.
     */
    private fun scenarioAtCaret(context: ConfigurationContext, root: String, target: VirtualFile): Caret? {
        if (target.isDirectory) return null
        val editor = CommonDataKeys.EDITOR.getData(context.dataContext) ?: return null
        if (FileDocumentManager.getInstance().getFile(editor.document) != target) return null
        val text = editor.document.immutableCharSequence
        val (name, line) = SaffronScenarioRun.scenarioAt(text, editor.caretModel.logicalPosition.line) ?: return null
        return Caret(name, line, SaffronScenarioRun.named(text, name) > 1, SaffronScenarioRun.indexAt(text, name, line)).takeIf { SaffronScenarioRun.runnerRunsScenarios(root) }
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
        /**
         * Whether the runner's walk finds a file in [dir], from the VFS (no disk read on a
         * context evaluation): its SKIPPED_DIRS skipped, and a linked folder counted without
         * reading through it, as the runner follows it wherever it leads.
         */
        fun containsSaffronFiles(dir: VirtualFile?, seen: MutableSet<String> = HashSet()): Boolean =
            dir != null && dir.isDirectory && dir.isInLocalFileSystem && seen.add(dir.canonicalPath ?: dir.path) &&
                dir.children.any { child ->
                    if (child.isDirectory) child.name !in SaffronProjectScan.RUNNER_SKIP_DIRS && (child.`is`(VFileProperty.SYMLINK) || containsSaffronFiles(child, seen))
                    else child.extension in FEATURE_EXTENSIONS
                }
    }
}
