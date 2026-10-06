package ai.saffron.jetbrains.run

import ai.saffron.jetbrains.ui.runnerAtLeast
import com.google.gson.JsonParser
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * One scenario run alone: `saffron run <file>:<scenario name>`, by name so
 * an edit that moves the scenario does not change what runs (by line only
 * for a scenario with no usable name). A Run lens starts it; the context
 * menu makes it for the scenario at the caret.
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

    /**
     * The lens's configuration, through [SaffronRunner.prepare]: a temporary
     * one of that name is reused and reset, a saved one is the user's and is
     * left alone, and no field of the Saffron template carries in. A scenario
     * of the same name in another file gets a name of its own: rewriting that
     * one's configuration would change what its Run tab reruns.
     */
    fun configuration(project: Project, root: String, target: String, scenario: String, mode: String): RunnerAndConfigurationSettings {
        val dir = FileUtil.toSystemIndependentName(root)
        val paths = SaffronCommand.joinPaths(listOf(target))
        // The temporary configuration prepare would reuse, past a saved one of the name ("Saffron: <name>").
        val taken = SaffronRunner.slot(project, name(scenario, mode)).second?.configuration as? SaffronRunConfiguration
        val elsewhere = taken != null && (taken.paths != paths || taken.workingDirectory != dir)
        return SaffronRunner.prepare(project, name(scenario, mode, target.substringBeforeLast(':').takeIf { elsewhere })) {
            it.workingDirectory = dir
            it.paths = paths
            it.replayOnly = mode == "replay"
            it.headed = mode == "headed"
        }
    }

    /** `file:name`, or `file:line` when the name is blank or the runner would read it as lines (`42`, `10:30`). */
    fun target(file: String, name: String, line: Int): String =
        if (name.isNotBlank() && !LINES.matches(name)) "$file:$name" else "$file:$line"

    /** The runner's own rule for the part after the file: digits, colon-separated, are lines. */
    private val LINES = Regex("\\d+(:\\d+)*")

    /** Whether the runner [root] runs (its own install, else npx's latest) takes `file:name` targets. */
    fun runnerRunsScenarios(root: String): Boolean {
        val version = runCatching {
            JsonParser.parseString(Files.readString(Path.of(root, "node_modules", "saffron-ai", "package.json"))).asJsonObject.get("version").asString
        }.getOrNull() ?: return true
        return runnerAtLeast(version, 0, 9, 8)
    }

    private val SCENARIO = Regex("^(Scenario Outline|Scenario Template|Scenario|Example):(.*)$")
    private val OTHER_BLOCK = Regex("^(Feature|Background|Rule|StepSet):")
    private val LANGUAGE = Regex("^\\s*#\\s*language\\s*:\\s*([\\w-]+)")

    /**
     * The scenario a 0-based [line] of [text] is in, as its name and 1-based
     * keyword line; null in the feature's header, a Background, a Rule's
     * header or a StepSet. A tag line, and a comment or blank line between
     * tags and their keyword, belong to the scenario tagged. English
     * keywords only: a file in another language (`# language: it`) finds
     * nothing, and the whole file runs.
     */
    fun scenarioAt(text: CharSequence, line: Int): Pair<String, Int>? {
        val lines = text.lines()
        // `# language:` is a comment above everything else.
        val language = lines.takeWhile { it.isBlank() || it.trim().startsWith("#") }.firstNotNullOfOrNull { LANGUAGE.find(it)?.groupValues?.get(1) }
        if (language != null && language != "en") return null
        val gap = { s: String -> s.isEmpty() || s.startsWith("#") }
        var at = line.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        var up = at
        while (up > 0 && gap(lines[up].trim())) up--
        if (lines.getOrElse(up) { "" }.trim().startsWith("@")) {
            while (at < lines.size - 1 && lines[at].trim().let { gap(it) || it.startsWith("@") }) at++
        }
        var found: Pair<String, Int>? = null
        var fence: String? = null
        for (i in 0..at) {
            val trimmed = lines.getOrElse(i) { "" }.trim()
            val marker = listOf("\"\"\"", "```").firstOrNull { trimmed.startsWith(it) }
            if (marker != null) {
                if (fence == null) fence = marker else if (fence == marker) fence = null
                continue
            }
            if (fence != null) continue
            val scenario = SCENARIO.find(trimmed)
            if (scenario != null) found = scenario.groupValues[2].trim() to i + 1
            else if (OTHER_BLOCK.containsMatchIn(trimmed)) found = null
        }
        return found
    }
}
