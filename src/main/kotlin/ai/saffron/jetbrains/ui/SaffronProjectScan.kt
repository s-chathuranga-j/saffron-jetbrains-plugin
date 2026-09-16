package ai.saffron.jetbrains.ui

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** One `.saffron` file as listed in the tool window. */
data class SaffronFile(val relativePath: String, val scenarios: Int, val stepSets: Int) {
    val isLibrary: Boolean get() = scenarios == 0 && stepSets > 0
    override fun toString(): String = relativePath
}

/** Totals of the latest run, from `.saffron/reports/latest.json`. */
data class LastRun(val green: Int, val yellow: Int, val red: Int, val costUsd: Double, val finishedAt: Instant?)

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

    private val SKIP_DIRS = setOf("node_modules", ".git", ".saffron", "dist", "build", "target", ".idea")
    private val SCENARIO = Regex("^\\s*Scenario( Outline| Template)?:")
    private val STEP_SET = Regex("^\\s*StepSet:")

    fun isSaffronProject(basePath: String?): Boolean {
        val base = basePath?.let(Path::of) ?: return false
        if (Files.exists(base.resolve("saffron.config.json"))) return true
        if (Files.isDirectory(base.resolve("node_modules/saffron-ai"))) return true
        val features = base.resolve("features")
        return Files.isDirectory(features) && Files.list(features).use { s -> s.anyMatch { it.extension == "saffron" } }
    }

    fun scan(basePath: String): SaffronProjectState {
        val base = Path.of(basePath)
        val files = mutableListOf<SaffronFile>()
        Files.walkFileTree(base, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
                if (dir != base && dir.name in SKIP_DIRS) java.nio.file.FileVisitResult.SKIP_SUBTREE
                else if (base.relativize(dir).nameCount > 12) java.nio.file.FileVisitResult.SKIP_SUBTREE
                else java.nio.file.FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (file.extension == "saffron") files += describe(base, file)
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
                costUsd = t.get("costUsd")?.asDouble ?: 0.0,
                finishedAt = root.get("finishedAt")?.asString?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }.getOrNull()
    }

    private fun countProposals(dir: Path): Int {
        if (!dir.isDirectory()) return 0
        return Files.walk(dir).use { s -> s.filter { it.extension == "json" }.count().toInt() }
    }
}
