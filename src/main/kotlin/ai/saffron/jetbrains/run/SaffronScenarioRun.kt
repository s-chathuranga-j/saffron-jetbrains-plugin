package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.ui.SaffronProjectScan
import ai.saffron.jetbrains.ui.runnerAtLeast
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil

/**
 * One scenario run alone: `saffron run <file>:<scenario name>`, by name so
 * an edit that moves the scenario does not change what runs (by line only
 * for a scenario with no usable name, or one whose name the file has
 * twice). A Run lens starts it; the context menu makes it for the scenario
 * at the caret.
 */
object SaffronScenarioRun {

    /** "run" (the agent may record and heal), "replay" (`--no-agent`) or "headed". */
    fun name(scenario: String, mode: String, file: String? = null): String {
        val run = "Run '$scenario'" + (file?.let { " in $it" } ?: "")
        return when (mode) {
            "replay" -> "$run (replay only)"
            "headed" -> "$run (headed)"
            else -> run
        }
    }

    /** The file of a `file:name` target, as the runner splits it: a name may hold ":". */
    internal fun fileOf(target: String): String = Regex("""^(.+?\.(?:saffron|feature)):""").find(target)?.groupValues?.get(1) ?: target

    /**
     * The lens's configuration, through [SaffronRunner.prepare]: a temporary
     * one of that name is reused and reset, a saved one is the user's and is
     * left alone, and no field of the Saffron template carries in. A scenario
     * of the same name in another file, or in another nested project, gets a
     * name of its own: rewriting that one's configuration would change what
     * its Run tab reruns.
     */
    fun configuration(project: Project, root: String, target: String, scenario: String, mode: String): RunnerAndConfigurationSettings {
        val dir = FileUtil.toSystemIndependentName(root)
        val paths = SaffronCommand.joinPaths(listOf(target))
        val file = fileOf(target)
        // The file's path from the IDE project tells nested projects' files apart.
        val inProject = project.basePath?.let { FileUtil.getRelativePath(FileUtil.toSystemIndependentName(it), "$dir/$file", '/') } ?: "$dir/$file"
        // The first name whose configuration (the one prepare would reuse, past a saved
        // one: "Saffron: <name>") is free or already this scenario's.
        val label = listOf(null, file, inProject, "$dir/$file").map { name(scenario, mode, it) }.firstOrNull {
            val taken = SaffronRunner.slot(project, it).second?.configuration as? SaffronRunConfiguration
            taken == null || (taken.paths == paths && taken.workingDirectory == dir)
        } ?: name(scenario, mode, "$dir/$file")
        return SaffronRunner.prepare(project, label) {
            it.workingDirectory = dir
            it.paths = paths
            it.replayOnly = mode == "replay"
            it.headed = mode == "headed"
        }
    }

    /**
     * `file:name`, or `file:line` when the name is blank, the runner would read it as lines
     * (`42`, `10:30`), or the file has [twice] a scenario of that name: the runner's name
     * selector takes the first.
     */
    fun target(file: String, name: String, line: Int, twice: Boolean = false): String =
        if (name.isNotBlank() && !LINES.matches(name) && !twice) "$file:$name" else "$file:$line"

    /** The runner's own rule for the part after the file: digits, colon-separated, are lines. */
    private val LINES = Regex("\\d+(:\\d+)*")

    /**
     * Whether the runner [root] runs (its own install, else what npx runs: the lowest version
     * package.json allows, or the newest without one) takes `file:name` targets.
     */
    fun runnerRunsScenarios(root: String): Boolean {
        val version = SaffronCommand.installedVersion(root) ?: SaffronCommand.npxVersion(root) ?: return true
        return runnerAtLeast(version, 0, 9, 8)
    }

    private val OTHER_BLOCK = Regex("^(Feature|Background|Rule|StepSet):")

    /**
     * The scenario a 0-based [line] of [text] is in, as its name and 1-based
     * keyword line; null in the feature's header, a Background, a Rule's
     * header or a StepSet. A tag line, and a comment or blank line between
     * tags and their keyword, belong to the scenario tagged. Scenarios are
     * found in the file's Gherkin language (`# language: it`), as the Files
     * tab counts them.
     */
    fun scenarioAt(text: CharSequence, line: Int): Pair<String, Int>? {
        val lines = text.lines()
        val gap = { s: String -> s.isEmpty() || s.startsWith("#") }
        var at = line.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        var up = at
        while (up > 0 && gap(lines[up].trim())) up--
        if (lines.getOrElse(up) { "" }.trim().startsWith("@")) {
            while (at < lines.size - 1 && lines[at].trim().let { gap(it) || it.startsWith("@") }) at++
        }
        val (i, name) = headings(lines, at).lastOrNull() ?: return null
        return name?.let { it to i + 1 }
    }

    /** How many scenarios of [text] are named [name]. */
    fun named(text: CharSequence, name: String): Int = text.lines().let { lines -> headings(lines, lines.size - 1).count { it.second == name } }

    /**
     * The block headings of [lines] up to index [last], outside doc strings: a scenario's
     * 0-based line and name, or another block's line and null.
     */
    private fun headings(lines: List<String>, last: Int): List<Pair<Int, String?>> {
        val found = mutableListOf<Pair<Int, String?>>()
        val keywords = SaffronProjectScan.scenarioKeywords(lines)
        var fence: String? = null
        for (i in 0..last) {
            val trimmed = lines.getOrElse(i) { "" }.trim()
            val marker = listOf("\"\"\"", "```").firstOrNull { trimmed.startsWith(it) }
            if (marker != null) {
                if (fence == null) fence = marker else if (fence == marker) fence = null
                continue
            }
            if (fence != null) continue
            val keyword = keywords.firstOrNull { trimmed.startsWith("$it:") }
            if (keyword != null) found += i to trimmed.substring(keyword.length + 1).trim()
            else if (OTHER_BLOCK.containsMatchIn(trimmed)) found += i to null
        }
        return found
    }
}
