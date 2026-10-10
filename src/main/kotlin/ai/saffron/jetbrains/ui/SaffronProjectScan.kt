package ai.saffron.jetbrains.ui

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
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
data class LastRun(val green: Int, val yellow: Int, val red: Int, val costUsd: Double?, val finishedAt: Instant?)

data class SaffronProjectState(
    val files: List<SaffronFile>,
    val lastRun: LastRun?,
    val pendingProposals: Int,
    val packageInstalled: Boolean,
    val hasReport: Boolean,
)

/**
 * Plain file-system reads (no VFS, no indexes): works in dumb mode and sees
 * what the CLI wrote a moment ago. Call off the EDT.
 */
object SaffronProjectScan {

    internal val SKIP_DIRS = setOf("node_modules", ".git", ".saffron", "dist", "build", "target", ".idea")
    private val SCENARIO = Regex("^\\s*(Scenario( Outline| Template)?|Example):")
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
     * The configured test folder: `features` in saffron.config.json, else the runner's "features";
     * relative to [base] or absolute, as `saffron status` reports featuresDir. "." is [base] itself.
     */
    fun featuresDir(base: Path): Path {
        val configured = runCatching {
            JsonParser.parseString(Files.readString(base.resolve("saffron.config.json"))).asJsonObject.get("features")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }.getOrNull() ?: "features"
        return base.toAbsolutePath().resolve(configured).normalize()
    }

    /** Whether [path] is in the test folder of the project at [base], and not under .saffron, node_modules, .git or a build folder. */
    fun inFeaturesDir(base: Path, path: Path): Boolean {
        val dir = featuresDir(base)
        val p = path.toAbsolutePath().normalize()
        return p.startsWith(dir) && dir.relativize(p).none { it.name in SKIP_DIRS }
    }

    fun scan(basePath: String): SaffronProjectState {
        val base = Path.of(basePath).toAbsolutePath().normalize()
        val files = mutableListOf<SaffronFile>()
        // The runner's test folder only: .feature files elsewhere are another tool's (Cucumber, Behat).
        val features = featuresDir(base)
        if (Files.isDirectory(features)) Files.walkFileTree(features, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
                // A folder with its own config is a nested project: its files are its own.
                if (dir != features && (dir.name in SKIP_DIRS || Files.exists(dir.resolve("saffron.config.json")))) java.nio.file.FileVisitResult.SKIP_SUBTREE
                else if (features.relativize(dir).nameCount > 12) java.nio.file.FileVisitResult.SKIP_SUBTREE
                else java.nio.file.FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (file.extension in FEATURE_EXTENSIONS) files += describe(base, file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): java.nio.file.FileVisitResult =
                java.nio.file.FileVisitResult.CONTINUE
        })
        files.sortBy { it.relativePath }
        val reports = base.resolve(".saffron/reports/latest.json")
        return SaffronProjectState(
            files = files,
            lastRun = readLastRun(reports),
            pendingProposals = countProposals(base.resolve(".saffron/proposals")),
            packageInstalled = Files.isDirectory(base.resolve("node_modules/saffron-ai")),
            hasReport = Files.exists(base.resolve(".saffron/reports/latest.html")),
        )
    }

    private fun describe(base: Path, file: Path): SaffronFile {
        var scenarios = 0
        var sets = 0
        runCatching {
            Files.newBufferedReader(file).useLines { lines ->
                for (line in lines) {
                    if (SCENARIO.containsMatchIn(line)) scenarios++ else if (STEP_SET.containsMatchIn(line)) sets++
                }
            }
        }
        return SaffronFile(base.relativize(file).toString().replace('\\', '/'), scenarios, sets)
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
