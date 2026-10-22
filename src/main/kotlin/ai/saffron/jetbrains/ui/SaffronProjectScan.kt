package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import com.google.gson.JsonParser
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Extensions of feature files the runner reads. */
internal val FEATURE_EXTENSIONS = setOf("saffron", "feature")

/** One `.saffron` or `.feature` file as listed in the tool window. */
data class SaffronFile(val relativePath: String, val scenarios: Int, val stepSets: Int) {
    val isLibrary: Boolean get() = scenarios == 0 && stepSets > 0
    override fun toString(): String = relativePath
}

/** Totals of the latest run, from `.saffron/reports/latest.json`. */
/** [costUsd] is null when the provider reports no price (Codex, Antigravity, Cursor): never a measured $0. */
data class LastRun(
    val green: Int,
    val yellow: Int,
    val red: Int,
    val costUsd: Double?,
    val finishedAt: Instant?,
    /** Cut short (Ctrl-C, Stop): [notRun] of its scenarios did not run, neither passed nor failed. */
    val interrupted: Boolean = false,
    val notRun: Int? = null,
) {
    /** The panel's line: an interrupted run never reads as a finished one. */
    fun summary(ago: String): String {
        val cost = costUsd?.let { "$" + "%.2f".format(it) } ?: "cost not reported"
        val stopped = if (interrupted) "Run interrupted (${notRun ?: "some"} not run): " else "Last run: "
        return "$stopped$green passed · $yellow pending review · $red failed · $cost$ago"
    }
}

data class SaffronProjectState(
    val files: List<SaffronFile>,
    val lastRun: LastRun?,
    val pendingProposals: Int,
    val packageInstalled: Boolean,
    val hasReport: Boolean,
    /** The test folder [files] were scanned in. */
    val featuresDir: Path? = null,
)

/**
 * Plain file-system reads (no VFS, no indexes): works in dumb mode and sees
 * what the CLI wrote a moment ago. Call off the EDT.
 */
object SaffronProjectScan {

    /** Folders skipped when looking for Saffron projects (discovery only). */
    internal val SKIP_DIRS = setOf("node_modules", ".git", ".saffron", "dist", "build", "target", ".idea")
    /** The runner's SKIPPED_DIRS: every other file in the test folder is one it runs, so one listed and runnable here. */
    internal val RUNNER_SKIP_DIRS = setOf(".saffron", "node_modules", ".git")
    private val STEP_SET = Regex("^\\s*StepSet:")

    fun isSaffronProject(basePath: String?): Boolean {
        val base = basePath?.let(Path::of) ?: return false
        if (Files.exists(base.resolve("saffron.config.json"))) return true
        if (Files.isDirectory(base.resolve("node_modules/saffron-ai"))) return true
        // .saffron only: a features/ folder of .feature files is any Cucumber or Behat project.
        val features = base.resolve("features")
        return Files.isDirectory(features) && Files.list(features).use { s -> s.anyMatch { it.extension == "saffron" } }
    }

    /**
     * The configured test folder: `features` in saffron.config.json, else (blank too) the runner's "features";
     * relative to [base] or absolute, as `saffron status` reports featuresDir. "." is [base] itself.
     */
    fun featuresDir(base: Path): Path {
        val configured = runCatching {
            JsonParser.parseString(Files.readString(base.resolve("saffron.config.json"))).asJsonObject.get("features")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "features"
        return base.toAbsolutePath().resolve(configured).normalize()
    }

    /**
     * Whether files listed for the test folder [shown] are stale now `saffron status` reports [reported]
     * (its featuresDir, relative to [base]); runners that report none never ask for a rescan.
     */
    fun testFolderMoved(base: Path, shown: Path?, reported: String?): Boolean =
        shown != null && reported != null && base.toAbsolutePath().resolve(reported.ifBlank { "features" }).normalize() != shown

    /**
     * Whether [path] is in the test folder of the project at [base], and not under .saffron, node_modules or .git.
     * Compared as the file system does: on macOS and Windows a `Features` folder is the configured `features`.
     */
    fun inFeaturesDir(base: Path, path: Path): Boolean {
        val dir = featuresDir(base)
        val p = path.toAbsolutePath().normalize()
        return FileUtil.isAncestor(dir.toString(), p.toString(), false) &&
            (p.nameCount == dir.nameCount || p.subpath(dir.nameCount, p.nameCount).none { it.name in RUNNER_SKIP_DIRS })
    }

    fun scan(basePath: String): SaffronProjectState {
        val base = Path.of(basePath).toAbsolutePath().normalize()
        val files = mutableListOf<SaffronFile>()
        // The runner's test folder only: .feature files elsewhere are another tool's (Cucumber, Behat).
        val features = featuresDir(base)
        for (file in runnerFiles(features)) files += describe(base, file)
        files.sortBy { it.relativePath }
        val reports = base.resolve(".saffron/reports/latest.json")
        return SaffronProjectState(
            files = files,
            lastRun = readLastRun(reports),
            pendingProposals = countProposals(base.resolve(".saffron/proposals")),
            packageInstalled = SaffronCommand.installedPackage(base.toString()) != null,
            hasReport = Files.exists(base.resolve(".saffron/reports/latest.html")),
            featuresDir = features,
        )
    }

    /**
     * The feature files the runner runs in [dir], by the paths it reads them at: its walk
     * (findSourceFiles), SKIPPED_DIRS skipped and linked folders followed wherever they lead.
     * Each folder is walked once by its real path, so a link loop or a Windows junction
     * leading back up ends, and real folders before any link, so a folder reachable both
     * ways keeps its own path. A link to a file counts when it leads to one. With [first],
     * stops at the first file found.
     */
    internal fun runnerFiles(dir: Path, first: Boolean = false): List<Path> {
        val files = mutableListOf<Path>()
        val seen = HashSet<Path>()
        val links = mutableListOf<Path>()
        fun walk(at: Path) {
            val real = runCatching { at.toRealPath() }.getOrNull() ?: return
            if (!seen.add(real)) return
            val entries = runCatching { Files.list(at).use { it.toList() } }.getOrNull() ?: return
            for (entry in entries) {
                if (first && files.isNotEmpty()) return
                val attrs = runCatching { Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: continue
                val feature = entry.extension in FEATURE_EXTENSIONS
                when {
                    attrs.isSymbolicLink && Files.isDirectory(entry) -> if (entry.name !in RUNNER_SKIP_DIRS) links.add(entry)
                    attrs.isSymbolicLink -> if (feature && Files.isRegularFile(entry)) files.add(entry)
                    attrs.isDirectory -> if (entry.name !in RUNNER_SKIP_DIRS) walk(entry)
                    attrs.isRegularFile -> if (feature) files.add(entry)
                }
            }
        }
        walk(dir)
        // Then the linked folders, each once by its real path (links found in them join the queue).
        var i = 0
        while (i < links.size && !(first && files.isNotEmpty())) walk(links[i++])
        return files
    }

    private fun describe(base: Path, file: Path): SaffronFile {
        val lines = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
        val (scenarios, sets) = count(lines)
        return SaffronFile(base.relativize(file).toString().replace('\\', '/'), scenarios, sets)
    }

    /** Scenarios (in the file's Gherkin language) and step sets of a file's [lines], doc strings left out. */
    internal fun count(lines: List<String>): Pair<Int, Int> {
        val scenario = scenarioKeywords(lines)
        val inDocstring = docstringMask(lines)
        var scenarios = 0
        var sets = 0
        lines.forEachIndexed { i, line ->
            if (inDocstring[i]) return@forEachIndexed
            val trimmed = line.trim()
            if (scenario.any { trimmed.startsWith("$it:") }) scenarios++ else if (STEP_SET.containsMatchIn(line)) sets++
        }
        return scenarios to sets
    }

    /** The Scenario and Scenario Outline keywords of [lines]' Gherkin language (English without one). */
    internal fun scenarioKeywords(lines: List<String>): List<String> =
        (SCENARIO_KEYWORDS[languageOf(lines)] ?: SCENARIO_KEYWORDS["en"].orEmpty()).take(2).flatten()

    /**
     * Each language's Scenario, Scenario Outline and Examples keywords, from the Gherkin the
     * runner parses with (a copy of the VS Code extension's assets/scenario-keywords.json).
     */
    private val SCENARIO_KEYWORDS: Map<String, List<List<String>>> by lazy {
        runCatching {
            val text = SaffronProjectScan::class.java.getResourceAsStream("/scenario-keywords.json")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
            com.google.gson.Gson().fromJson<Map<String, List<List<String>>>>(text, object : com.google.gson.reflect.TypeToken<Map<String, List<List<String>>>>() {}.type)
        }.getOrNull() ?: mapOf("en" to listOf(listOf("Example", "Scenario"), listOf("Scenario Outline", "Scenario Template"), listOf("Examples", "Scenarios")))
    }

    /** A file's `# language: fr`, a comment above everything else, as the runner's parser reads it. */
    internal fun languageOf(lines: List<String>): String? =
        lines.takeWhile { it.isBlank() || it.trim().startsWith("#") }
            .firstNotNullOfOrNull { Regex("^\\s*#\\s*language\\s*:\\s*([\\w-]+)").find(it)?.groupValues?.get(1) }

    /** Per line, whether it sits inside a """ or ``` doc string (fence lines included). */
    internal fun docstringMask(lines: List<String>): List<Boolean> {
        var fence: String? = null
        return lines.map { line ->
            val marker = listOf("\"\"\"", "```").firstOrNull { line.trimStart().startsWith(it) }
            if (marker != null && (fence == null || fence == marker)) {
                fence = if (fence == null) marker else null
                true
            } else {
                fence != null
            }
        }
    }

    private fun readLastRun(report: Path): LastRun? {
        if (!Files.exists(report)) return null
        return runCatching {
            val root = JsonParser.parseString(Files.readString(report)).asJsonObject
            val t = root.getAsJsonObject("totals")
            LastRun(
                green = t.get("green").asInt,
                yellow = t.get("yellow").asInt,
                red = t.get("red").asInt,
                // The runner's rule: AI work with no dollar figure is "not reported".
                costUsd = (t.get("costUsd")?.asDouble ?: 0.0).takeIf { it > 0 || (t.get("aiCalls")?.asInt ?: 0) == 0 },
                finishedAt = root.get("finishedAt")?.asString?.let { runCatching { Instant.parse(it) }.getOrNull() },
                interrupted = root.get("interrupted")?.asBoolean == true,
                notRun = root.get("notRun")?.takeIf { it.isJsonPrimitive }?.asInt,
            )
        }.getOrNull()
    }

    private fun countProposals(dir: Path): Int {
        if (!dir.isDirectory()) return 0
        return Files.walk(dir).use { s -> s.filter { it.extension == "json" }.count().toInt() }
    }
}

/** Saffron projects under the IDE's root: the runner's own nesting rule, from the plugin's side. */
object SaffronRoots {

    /**
     * Every folder at most [maxDepth] levels below [top] holding a
     * saffron.config.json; else [top] alone when it looks like a Saffron
     * project without one; else nothing.
     */
    fun discover(top: Path, maxDepth: Int = 4): List<Path> {
        val found = mutableListOf<Path>()
        fun walk(dir: Path, depth: Int) {
            if (Files.exists(dir.resolve("saffron.config.json"))) found.add(dir)
            if (depth == maxDepth) return
            val children = runCatching { Files.newDirectoryStream(dir) { it.isDirectory() && it.name !in SaffronProjectScan.SKIP_DIRS }.use { it.toList() } }
                .getOrDefault(emptyList())
            for (child in children.sorted()) walk(child, depth + 1)
        }
        if (Files.isDirectory(top)) walk(top, 0)
        if (found.isEmpty() && SaffronProjectScan.isSaffronProject(top.toString())) found.add(top)
        return found
    }

    /** The runner's projectRootFor: the nearest folder above [file] with a saffron.config.json, never above [top]; else [top]. */
    fun rootFor(file: Path, top: Path): Path {
        val bound = top.toAbsolutePath().normalize()
        var dir = file.toAbsolutePath().normalize().parent
        if (dir == null || !dir.startsWith(bound)) return top
        while (dir != null) {
            if (Files.exists(dir.resolve("saffron.config.json"))) return dir
            if (dir == bound) break
            dir = dir.parent
        }
        return top
    }
}
