package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import com.google.gson.JsonObject
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

/** "2 failed (1 more not run)": a red the app did not cause is not run, as the runner's status and results page say. */
internal fun failedCount(red: Int, blocked: Int): String = "${maxOf(0, red - blocked)} failed" + if (blocked > 0) " ($blocked more not run)" else ""

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
    /** Reds the app did not cause (a stale recording without the agent, the environment): counted in [red], shown as not run. */
    val blocked: Int = 0,
    /** The teardown failed: the runner exits 2 for the run, even when every scenario passed. */
    val teardownFailed: Boolean = false,
    /** Rows a run of named scenarios kept from earlier runs: the counts above are this run's alone. */
    val earlierRows: Int = 0,
) {
    /** The panel's line: an interrupted run never reads as a finished one. */
    fun summary(ago: String): String {
        val cost = costUsd?.let { "$" + "%.2f".format(it) } ?: "cost not reported"
        val stopped = if (interrupted) "Run interrupted (${notRun ?: "some"} not run): " else "Last run: "
        return "$stopped$green passed · $yellow pending review · ${failedCount(red, blocked)} · $cost$ago" + (if (teardownFailed) " · teardown failed" else "") +
            if (earlierRows > 0) " · this run; $earlierRows more in the report from earlier runs" else ""
    }
}

/** Whether a run report's teardown failed, as the runner's hookFailed reads it: any exit code but 0, none included. */
internal fun teardownFailed(report: JsonObject): Boolean =
    report.get("hooks")?.takeIf { it.isJsonArray }?.asJsonArray?.any { h ->
        h.isJsonObject && h.asJsonObject.get("phase")?.takeIf { it.isJsonPrimitive }?.asString == "teardown" &&
            h.asJsonObject.get("exitCode").let { it == null || !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber || it.asDouble != 0.0 }
    } == true

/** Whether the run that started at [startedAt] failed its teardown, from the project's latest.json; false when that report is another run's. Reads the disk. */
internal fun teardownFailedFor(base: Path, startedAt: String): Boolean = runCatching {
    val root = JsonParser.parseString(Files.readString(base.resolve(".saffron/reports/latest.json"))).asJsonObject
    root.get("startedAt")?.takeIf { it.isJsonPrimitive }?.asString == startedAt && teardownFailed(root)
}.getOrDefault(false)

data class SaffronProjectState(
    val files: List<SaffronFile>,
    val lastRun: LastRun?,
    val pendingProposals: Int,
    val packageInstalled: Boolean,
    /** The test folder [files] were scanned in. */
    val featuresDir: Path? = null,
    /** Why the runner refuses the config's `db` (every command stops on it), or null. */
    val configProblem: String? = null,
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
            featuresDir = features,
            configProblem = configProblem(base),
        )
    }

    /** The project's saffron.config.json `db` problem, as the runner words it; null without one, or for a file that is not JSON. */
    internal fun configProblem(base: Path): String? = runCatching {
        val config = strictJson(Files.readString(base.resolve("saffron.config.json")).removePrefix("\uFEFF"))
        if (config.isJsonObject && config.asJsonObject.has("db")) dbSettingsProblem(config.asJsonObject.get("db")) else null
    }.getOrNull()

    /**
     * [text] as JSON.parse reads it, or a throw: Gson's JsonParser is lenient (single quotes, unquoted names, comments,
     * a trailing value), and would check a file the runner refuses as not JSON at all.
     */
    internal fun strictJson(text: String): com.google.gson.JsonElement {
        val reader = com.google.gson.stream.JsonReader(java.io.StringReader(text)).apply { isLenient = false }
        val element = com.google.gson.Gson().getAdapter(com.google.gson.JsonElement::class.java).read(reader)
        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw com.google.gson.JsonSyntaxException("more than one value")
        return element
    }

    /**
     * The feature files the runner runs in [dir], by the paths it reads them at: its walk
     * (findSourceFiles), SKIPPED_DIRS skipped and linked folders followed wherever they lead.
     * Each folder is walked once by its real path, so a link loop or a Windows junction
     * leading back up ends, and real folders before any link, so a folder reachable both
     * ways keeps its own path. A link to a file counts when it leads to one. With [first],
     * stops at the first file found. As the runner, a broken or looping link is skipped,
     * and any other folder it cannot read throws: hiding it would drop its scenarios.
     */
    internal fun runnerFiles(dir: Path, first: Boolean = false): List<Path> {
        if (!Files.exists(dir)) return emptyList()
        val files = mutableListOf<Path>()
        val seen = HashSet<Path>()
        val links = mutableListOf<Path>()
        fun walk(at: Path, linked: Boolean) {
            val (real, entries) = try {
                at.toRealPath().let { real -> real to (if (real in seen) return else Files.list(at).use { it.toList() }) }
            } catch (e: java.nio.file.FileSystemException) {
                // ENOENT and ELOOP, the runner's skippable errors.
                if (linked && (e is java.nio.file.NoSuchFileException || e.reason?.contains("symbolic links") == true)) return
                throw e
            }
            seen.add(real)
            for (entry in entries) {
                if (first && files.isNotEmpty()) return
                val attrs = runCatching { Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: continue
                val feature = entry.extension in FEATURE_EXTENSIONS
                when {
                    isLink(attrs) && Files.isDirectory(entry) -> if (entry.name !in RUNNER_SKIP_DIRS) links.add(entry)
                    isLink(attrs) -> if (feature && Files.isRegularFile(entry)) files.add(entry)
                    attrs.isDirectory -> if (entry.name !in RUNNER_SKIP_DIRS) walk(entry, false)
                    attrs.isRegularFile -> if (feature) files.add(entry)
                }
            }
        }
        walk(dir, false)
        // Then the linked folders, each once by its real path (links found in them join the queue).
        var i = 0
        while (i < links.size && !(first && files.isNotEmpty())) walk(links[i++], true)
        return files
    }

    /**
     * A link as the runner (Node) sees one: a symbolic link, or on Windows a junction,
     * which Java reports as a directory that is also "other" (a reparse point).
     */
    internal fun isLink(attrs: BasicFileAttributes): Boolean = attrs.isSymbolicLink || (attrs.isDirectory && attrs.isOther)

    private fun describe(base: Path, file: Path): SaffronFile {
        // Bytes that are not UTF-8 read as replacement characters, as the runner reads them: readAllLines threw on
        // one, and a Latin-1 file the runner runs was listed with 0 scenarios (found in review).
        val lines = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8).lines() }.getOrDefault(emptyList())
        val (scenarios, sets) = count(lines)
        return SaffronFile(relativeName(base, file), scenarios, sets)
    }

    /**
     * [file] relative to [base], or its own path where there is no relative one (a test folder on
     * another Windows drive, or a junction to one), as the runner's path.relative gives it. relativize
     * threw there, and the Files tab listed nothing (found in review).
     */
    internal fun relativeName(base: Path, file: Path): String =
        runCatching { base.relativize(file).toString() }.getOrElse { file.toString() }.replace('\\', '/')

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

    /** The file's Feature, Background and Rule keywords: the blocks that are no scenario. */
    internal fun blockKeywords(lines: List<String>): List<String> =
        (SCENARIO_KEYWORDS[languageOf(lines)] ?: SCENARIO_KEYWORDS["en"])?.getOrNull(3) ?: listOf("Feature", "Business Need", "Ability", "Background", "Rule")

    /**
     * Each language's Scenario, Scenario Outline, Examples and other block (Feature, Background, Rule) keywords, from the Gherkin the
     * runner parses with (a copy of the VS Code extension's assets/scenario-keywords.json).
     */
    private val SCENARIO_KEYWORDS: Map<String, List<List<String>>> by lazy {
        runCatching {
            val text = SaffronProjectScan::class.java.getResourceAsStream("/scenario-keywords.json")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
            com.google.gson.Gson().fromJson<Map<String, List<List<String>>>>(text, object : com.google.gson.reflect.TypeToken<Map<String, List<List<String>>>>() {}.type)
        }.getOrNull() ?: mapOf("en" to listOf(listOf("Example", "Scenario"), listOf("Scenario Outline", "Scenario Template"), listOf("Examples", "Scenarios")))
    }

    /**
     * A file's `# language: fr`, a comment above everything else, as the runner's parser reads it (gherkinLanguage):
     * the whole comment, so `# language: fr, then en` names no language. A byte order mark and a no-break space
     * are spaces there, as JavaScript reads them.
     */
    internal fun languageOf(lines: List<String>): String? =
        lines.asSequence().map { it.replace('\uFEFF', ' ') }.takeWhile { it.isBlank() || it.trim().startsWith("#") }
            .firstNotNullOfOrNull { LANGUAGE.find(it)?.groupValues?.get(1) }

    private val LANGUAGE = Regex("(?U)^\\s*#\\s*language\\s*:\\s*([a-zA-Z\\-_]+)\\s*$")

    /** Each language's step keywords as written before the step text ("Given "), from the runner's Gherkin (the VS Code extension's step-keywords.json). */
    private val STEP_KEYWORDS: Map<String, List<String>> by lazy {
        runCatching {
            val text = SaffronProjectScan::class.java.getResourceAsStream("/step-keywords.json")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
            com.google.gson.Gson().fromJson<Map<String, List<String>>>(text, object : com.google.gson.reflect.TypeToken<Map<String, List<String>>>() {}.type)
        }.getOrNull() ?: mapOf("en" to listOf("* ", "Given ", "When ", "Then ", "And ", "But "))
    }

    private val DEFINITION = Regex("^StepSet:\\s*\\S.*$")
    private val INVOCATION = Regex("^StepSet\\s+[^\\s:].*$")

    /**
     * Per line, whether it sits inside a """ or ``` doc string (fence lines included), as the runner's
     * docStringLines reads one: a fence opens only right after a step in a Scenario, Background, StepSet or
     * Fixture, so `"""Orders""" lists ...` in a description is text, not a doc string hiding every line after it.
     */
    internal fun docstringMask(lines: List<String>): List<Boolean> {
        val language = languageOf(lines)
        val table = SCENARIO_KEYWORDS[language] ?: SCENARIO_KEYWORDS["en"].orEmpty()
        val steps = STEP_KEYWORDS[language] ?: STEP_KEYWORDS["en"].orEmpty()
        fun header(keywords: List<String>) = { line: String -> keywords.any { line.startsWith("$it:") } }
        // scenario, outline, examples, (feature, background, rule), feature, background, rule
        val opensBody = header(table.getOrElse(0) { emptyList() } + table.getOrElse(1) { emptyList() } + table.getOrElse(5) { listOf("Background") })
        val closesBody = header(table.getOrElse(4) { listOf("Feature") } + table.getOrElse(6) { listOf("Rule") } + table.getOrElse(2) { emptyList() })
        var fence: String? = null
        var afterStep = false
        var inBody = false
        return lines.map { raw ->
            val line = raw.trim()
            val delimiter = listOf("\"\"\"", "```").firstOrNull { line.startsWith(it) }
            if (fence != null) {
                if (delimiter == fence) fence = null
                return@map true
            }
            if (delimiter != null && afterStep) {
                fence = delimiter
                afterStep = false
                return@map true
            }
            if (line.isEmpty() || line.startsWith("#")) return@map false
            if (opensBody(line) || DEFINITION.matches(line) || line.startsWith("Fixture:")) inBody = true
            else if (closesBody(line)) inBody = false
            // A table row keeps a step's context.
            if (!line.startsWith("|")) afterStep = inBody && (steps.any { line.startsWith(it) } || INVOCATION.matches(line))
            false
        }
    }

    private fun readLastRun(report: Path): LastRun? {
        if (!Files.exists(report)) return null
        return runCatching {
            val root = JsonParser.parseString(Files.readString(report)).asJsonObject
            // A run of named scenarios folded into the earlier report: this run's own totals and rows, as the runner's
            // report headlines it (kpiStrip, src/report/html.ts).
            val scenarios = root.getAsJsonArray("scenarios")?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
            val invocation = root.get("invocation")?.takeIf { it.isJsonObject }?.asJsonObject?.get("totals")?.takeIf { it.isJsonObject }?.asJsonObject
            val fresh = scenarios.filterNot { keptFromEarlier(root, it) }
            val earlier = if (invocation != null) scenarios.size - fresh.size else 0
            val t = if (earlier > 0) invocation!! else root.getAsJsonObject("totals")
            val rows = if (earlier > 0) fresh else scenarios
            LastRun(
                green = t.get("green").asInt,
                yellow = t.get("yellow").asInt,
                red = t.get("red").asInt,
                // The runner's rule: AI work with no dollar figure is "not reported".
                costUsd = (t.get("costUsd")?.asDouble ?: 0.0).takeIf { it > 0 || (t.get("aiCalls")?.asInt ?: 0) == 0 },
                finishedAt = root.get("finishedAt")?.asString?.let { runCatching { Instant.parse(it) }.getOrNull() },
                interrupted = root.get("interrupted")?.asBoolean == true,
                notRun = root.get("notRun")?.takeIf { it.isJsonPrimitive }?.asInt,
                teardownFailed = teardownFailed(root),
                blocked = rows.count { it.get("status")?.asString == "red" && it.get("blocked")?.isJsonPrimitive == true },
                earlierRows = earlier,
            )
        }.getOrNull()
    }

    /** The runner's listProposals: the .json files in each proposals/<feature> folder, two levels and no deeper. */
    internal fun countProposals(dir: Path): Int {
        if (!dir.isDirectory()) return 0
        return Files.list(dir).use { features ->
            features.filter { it.isDirectory() }.toList().sumOf { f -> Files.list(f).use { s -> s.filter { it.name.endsWith(".json") }.count().toInt() } }
        }
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

private val DATABASE_NAME = Regex("^[A-Za-z][A-Za-z0-9_-]*$")
private val ENV_URL = Regex("^\\{env:[A-Za-z_][A-Za-z0-9_]*\\}$")

/**
 * The runner's dbSettingsProblem (src/db/index.ts): why the config's `db` cannot be used, in its words, or null.
 * One database, or several by name under `db.databases`, never both. [db] is the value of the `db` key.
 */
internal fun dbSettingsProblem(db: com.google.gson.JsonElement): String? {
    if (!db.isJsonObject) return "db in saffron.config.json must be an object, such as { \"url\": \"{env:DATABASE_URL}\" }"
    val obj = db.asJsonObject
    val own = obj.keySet().filter { it != "databases" }
    if (!obj.has("databases")) return oneDatabaseProblem(obj, "db")
    if (own.isNotEmpty()) return "db in saffron.config.json has both settings of one database (${own.joinToString(", ") { "\"$it\"" }}) and databases: put every database under db.databases, by name (a step that names no database uses the one named \"default\"), or drop databases"
    val databases = obj.get("databases")
    if (!databases.isJsonObject || databases.asJsonObject.size() == 0) return "db.databases in saffron.config.json must name each database, such as { \"default\": { \"url\": \"{env:DATABASE_URL}\" }, \"orders\": { \"url\": \"{env:ORDERS_DB_URL}\" } }"
    for ((name, settings) in databases.asJsonObject.entrySet()) {
        if (!DATABASE_NAME.matches(name)) return "db.databases.\"$name\" in saffron.config.json: a database name is letters, digits, \"-\" and \"_\", starting with a letter"
        if (!settings.isJsonObject) return "db.databases.$name in saffron.config.json must be an object, such as { \"url\": \"{env:DATABASE_URL}\" }"
        oneDatabaseProblem(settings.asJsonObject, "db.databases.$name")?.let { return it }
    }
    return null
}

/** The runner's oneDatabaseProblem: one database's settings, [where] being "db" or "db.databases.<name>". */
private fun oneDatabaseProblem(db: com.google.gson.JsonObject, where: String): String? {
    val rest = db.keySet().filter { it !in setOf("url", "writes", "allow", "timeout", "cleanup", "redactColumns") }
    if (rest.isNotEmpty()) return "$where in saffron.config.json has ${rest.joinToString(", ") { "\"$it\"" }}, which Saffron does not read: it takes url, writes, allow, timeout, cleanup and redactColumns" + if (where == "db") ", or databases" else ""
    fun string(e: com.google.gson.JsonElement?) = e?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    // A JSON null is a value of the key, as JavaScript reads it: only an absent key is undefined.
    val timeout = db.get("timeout")
    if (timeout != null && !(timeout.isJsonPrimitive && timeout.asJsonPrimitive.isNumber && timeout.asDouble.isFinite() && timeout.asDouble >= 0)) return "$where.timeout in saffron.config.json must be a number of milliseconds, 0 or more, without quotes"
    val cleanup = db.get("cleanup")
    if (cleanup != null && string(cleanup) !in listOf("all", "inserts", "none")) return "$where.cleanup in saffron.config.json must be \"all\", \"inserts\" or \"none\""
    val url = string(db.get("url"))
    if (url == null || !ENV_URL.matches(url.trim())) return "$where.url in saffron.config.json must read the connection string from the environment, such as \"{env:DATABASE_URL}\", so the password never sits in the file"
    val writes = db.get("writes")
    if (writes != null && !(writes.isJsonPrimitive && writes.asJsonPrimitive.isBoolean)) return "$where.writes in saffron.config.json must be true or false, without quotes"
    fun names(e: com.google.gson.JsonElement?) = e == null || (e.isJsonArray && e.asJsonArray.all { string(it)?.isNotBlank() == true })
    if (!names(db.get("allow"))) return "$where.allow in saffron.config.json must list host or database names, such as [\"localhost\", \"shop_test\"]"
    if (!names(db.get("redactColumns"))) return "$where.redactColumns in saffron.config.json must list column names, such as [\"ssn\", \"date_of_birth\"]"
    return null
}
