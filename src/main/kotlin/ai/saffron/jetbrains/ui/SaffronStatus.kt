package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The runner's `saffron status --json`, as data classes. One implementation
 * in the runner feeds every tab here and the VS Code extension alike.
 */
class StatusScenario(
    val name: String = "",
    val line: Int? = null,
    val tags: List<String> = emptyList(),
    val outline: Boolean = false,
    val rows: Int = 1,
    val cached: Boolean = false,
    val proposal: Boolean = false,
    val lastStatus: String? = null,
)

class StatusFeature(
    val path: String = "",
    val name: String = "",
    val scenarios: List<StatusScenario> = emptyList(),
    val stepSets: Int = 0,
    val error: String? = null,
)

class StatusTag(val tag: String = "", val scenarios: Int = 0)

class StatusProposal(
    val file: String = "",
    val feature: String = "",
    val scenario: String = "",
    val mode: String = "record",
    val createdAt: String = "",
    val verified: Boolean? = null,
    val proofError: String? = null,
    /** Why the proposal can no longer be accepted as reviewed; null while it is still bound to what it was made against. */
    val stale: String? = null,
    val unbound: Boolean? = null,
    val adaptations: List<String> = emptyList(),
    val narrative: String = "",
    val suggestedFeatureEdit: String? = null,
    val recordedFor: String? = null,
    val aiCalls: Int = 0,
    val costUsd: Double? = null,
    /** sha256 of the file as listed; absent from runners before 0.9.0. */
    val revision: String? = null,
    /** The CI run `saffron import` brought this very proposal from; absent for one filed here. */
    val importedFrom: ImportedFrom? = null,
) {
    /**
     * What accept and reject are given: the file bound to the revision shown,
     * so a proposal a later run filed under the same name is refused, not
     * decided unseen. A plain file for an older runner.
     */
    val target: String get() = if (revision != null) "$file#$revision" else file
}

/** Where an imported proposal came from: the run ("github:901") and its link, the branch and commit, the shard. */
class ImportedFrom(
    val repository: String? = null,
    val branch: String? = null,
    val commit: String? = null,
    /** The branch head when CI tested a merge commit (a pull request build). */
    val headCommit: String? = null,
    val run: String? = null,
    val attempt: String? = null,
    val url: String? = null,
    val shard: ImportedShard? = null,
) {
    /** "from run 901", for a row. */
    val label: String
        get() = run?.substringAfter(':')?.takeIf { it.isNotBlank() }?.let { "from run $it" } ?: "imported"

    /** "imported from run github:901 (https://...) on main @ 1a2b3c4 · shard 2 of 4", for the details. */
    val description: String
        get() = listOfNotNull(
            "imported from run ${run ?: "(no CI run recorded)"}" + (url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { " ($it)" } ?: ""),
            if (branch != null || commit != null) "on ${branch ?: "a detached HEAD"}" + ((headCommit ?: commit)?.let { " @ ${it.take(7)}" } ?: "") else null,
            shard?.let { "shard ${it.index} of ${it.total}" },
        ).joinToString(" · ")
}

class ImportedShard(val index: Int = 0, val total: Int = 0)

class StatusTotals(
    val scenarios: Int = 0,
    val green: Int = 0,
    val yellow: Int = 0,
    val red: Int = 0,
    val aiCalls: Int = 0,
    /** Null when the provider reports no price: not reported, never a measured $0. */
    val costUsd: Double? = null,
    val plan: StatusPlan? = null,
    val models: List<String>? = null,
)

class StatusPlan(val subscriptionType: String = "", val fiveHourBefore: Double? = null, val fiveHourAfter: Double? = null)

/** sha256: the digest of the file that run wrote; absent from runners before digests. */
class StatusEvidence(val kind: String = "failure", val file: String = "", val step: String? = null, val sha256: String? = null)

/**
 * The evidence file's bytes if they are still the ones that run wrote. The
 * artifact paths are fixed, so a later run (in a terminal, in CI) replaces
 * the file behind a row still on screen. Null when the file is gone or replaced.
 */
fun currentEvidence(file: java.io.File, sha256: String?): ByteArray? {
    val bytes = try { file.readBytes() } catch (e: java.io.IOException) { return null }
    if (sha256 == null) return bytes
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return if (digest == sha256) bytes else null
}

/**
 * Where the checked copies go: this IDE's own per-user system folder. A temp
 * folder is shared by every user of a Linux machine, and nothing cleans it.
 */
fun evidenceCopies(): java.io.File = PathManager.getSystemDir().resolve("saffron-evidence").toFile()

private const val COPY_LIFETIME_MS = 24 * 60 * 60 * 1000L

/**
 * The checked bytes in a file of their own: what the editor opens, so a run
 * writing the original between the check and the editor's read cannot put
 * its picture in this row. One folder per digest. Every picture is named
 * failure.jpg or before-heal.jpg, so the copy takes its scenario folder's
 * name too (buy-shoes-failure.jpg), for the editor tab to say whose it is.
 * Copies untouched for a day are removed.
 */
fun checkedCopy(bytes: ByteArray, sha256: String, original: java.io.File, root: java.io.File = evidenceCopies()): java.io.File {
    val dir = java.io.File(root, sha256.take(16))
    // Throws with the reason, where File.mkdirs() would only say false.
    java.nio.file.Files.createDirectories(dir.toPath())
    // Touched first, so another open pruning at the same moment keeps it.
    val now = System.currentTimeMillis()
    dir.setLastModified(now)
    val copy = java.io.File(dir, listOfNotNull(original.parentFile?.name, original.name).joinToString("-"))
    copy.writeBytes(bytes)
    root.listFiles()?.filter { it != dir && it.lastModified() < now - COPY_LIFETIME_MS }?.forEach { it.deleteRecursively() }
    return copy
}

/** A scenario of the last run that was not green, with the screenshots taken. */
class StatusAttention(
    val feature: String = "",
    val scenario: String = "",
    val baseScenario: String = "",
    val status: String = "red",
    val error: String? = null,
    val failedStep: String? = null,
    val evidence: List<StatusEvidence> = emptyList(),
)

class StatusLastRun(
    val startedAt: String = "",
    val finishedAt: String = "",
    val totals: StatusTotals = StatusTotals(),
    val reportHtml: String? = null,
    /** Null when the runner predates failure screenshots: absent, not empty. */
    val attention: List<StatusAttention>? = null,
)

class StatusHistoryRun(val startedAt: String = "", val green: Int = 0, val yellow: Int = 0, val red: Int = 0, val aiCalls: Int = 0, val costUsd: Double? = null)

class DuplicateWording(val steps: List<String> = emptyList(), val actions: String = "")

class StatusVocabulary(
    val steps: Int = 0,
    val recorded: Int = 0,
    val unrecorded: Int = 0,
    val stepSets: Int = 0,
    val divergent: List<String> = emptyList(),
    val duplicateWordings: List<DuplicateWording> = emptyList(),
)

/** A cache or proposal no scenario owns any more; `saffron prune` removes it. */
class StatusOrphan(
    val file: String = "",
    val kind: String = "cache",
    val feature: String? = null,
    val scenario: String? = null,
    val reason: String = "unknown",
)

/** A proposal file the runner could not read, and what is wrong with it. */
class UnreadableProposal(val file: String = "", val problem: String = "")

class StatusConfig(val file: String? = null, val effective: JsonObject = JsonObject())

class ProjectStatus(
    val version: String = "",
    val packageInstalled: Boolean = false,
    val config: StatusConfig = StatusConfig(),
    val features: List<StatusFeature> = emptyList(),
    val tags: List<StatusTag> = emptyList(),
    val proposals: List<StatusProposal> = emptyList(),
    /** Absent from runners before 0.9.2. */
    val unreadableProposals: List<UnreadableProposal> = emptyList(),
    val lastRun: StatusLastRun? = null,
    val history: List<StatusHistoryRun> = emptyList(),
    val vocabulary: StatusVocabulary = StatusVocabulary(),
    /** Null when the runner predates `saffron prune`: absent, not empty. */
    val orphans: List<StatusOrphan>? = null,
)

/** Result of one load: the status, or why it is unavailable. */
class StatusLoad(val status: ProjectStatus?, val error: String?)

fun interface StatusListener {
    fun statusChanged(load: StatusLoad)
}

/**
 * Project-level cache of the status. Tabs subscribe; anything that changes
 * the project (a run finishing, files or proposals changing) calls
 * [refresh], and the load runs once off the EDT for all subscribers.
 */
@Service(Service.Level.PROJECT)
class SaffronStatusService(private val project: Project) : Disposable {

    @Volatile
    var latest: StatusLoad = StatusLoad(null, null)
        private set

    /** Saffron projects under the IDE root, as last discovered; empty until [discoverRoots] first runs. */
    @Volatile
    var roots: List<Path> = emptyList()
        private set

    /** Walks the file system: call off the EDT. */
    fun discoverRoots(): List<Path> =
        (project.basePath?.let { SaffronRoots.discover(Path.of(it)) } ?: emptyList()).also { roots = it }

    init {
        // Load once the project is trusted, not only at the next refresh.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TrustedProjectsListener.TOPIC,
            object : TrustedProjectsListener {
                override fun onProjectTrusted(project: Project) {
                    if (project == this@SaffronStatusService.project) refresh()
                }
            },
        )
    }

    /** The project the tool window shows: the one chosen when there are several, else the only one, else the IDE root. */
    val root: Path
        get() {
            val chosen = PropertiesComponent.getInstance(project).getValue(ROOT_KEY)?.let(Path::of)
            return chosen?.takeIf { it in roots } ?: roots.firstOrNull() ?: Path.of(project.basePath ?: "")
        }

    fun choose(root: Path) {
        PropertiesComponent.getInstance(project).setValue(ROOT_KEY, root.toString())
        refresh()
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val loading = AtomicBoolean(false)

    /** Debounced reload, then every listener on the EDT. */
    fun refresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ load() }, 300)
    }

    private fun load() {
        if (!loading.compareAndSet(false, true)) {
            refresh()
            return
        }
        try {
            discoverRoots()
            val result = if (SaffronCommand.trusted(project)) runStatus(root.toString()) else StatusLoad(null, UNTRUSTED)
            latest = result
            ApplicationManager.getApplication().invokeLater(
                { project.messageBus.syncPublisher(TOPIC).statusChanged(result) },
                project.disposed,
            )
        } finally {
            loading.set(false)
        }
    }

    override fun dispose() {}

    companion object {
        private const val ROOT_KEY = "ai.saffron.jetbrains.root"

        /** Shown instead of running anything in a project the user has not trusted. */
        const val UNTRUSTED = "Trust the project to load Saffron status"

        val TOPIC: Topic<StatusListener> = Topic.create("Saffron status", StatusListener::class.java)

        fun getInstance(project: Project): SaffronStatusService = project.getService(SaffronStatusService::class.java)

        /** `saffron status --json` for a project directory; never throws. */
        fun runStatus(basePath: String?): StatusLoad {
            basePath ?: return StatusLoad(null, "no project directory")
            return try {
                val command: GeneralCommandLine = SaffronCommand.base(basePath).withParameters("status", "--json")
                val output = ExecUtil.execAndGetOutput(command, 60_000)
                if (output.exitCode != 0 || output.stdout.isBlank()) {
                    StatusLoad(null, output.stderr.takeIf { it.isNotBlank() }?.let(::runnerError) ?: "saffron status exited with ${output.exitCode}")
                } else {
                    StatusLoad(Gson().fromJson(output.stdout, ProjectStatus::class.java), null)
                }
            } catch (e: Exception) {
                StatusLoad(null, e.message ?: e.toString())
            }
        }
    }
}

/**
 * The line of a runner's stderr that says what went wrong: an `Error:` line
 * or one of saffron's own, else one that mentions an error (`npm error ...`,
 * commander's `error: ...`). Never Node's crash trailer (`Node.js v22`), a
 * stack frame, or an npm or Node warning.
 */
internal fun runnerError(stderr: String): String {
    val lines = stderr.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val noise = Regex("""^(Node\.js v|at |npm warn|\(node:\d+\)|Warning:)""", RegexOption.IGNORE_CASE)
    val rest = lines.filterNot { noise.containsMatchIn(it) }
    return lines.firstOrNull { it.contains("Error:") || it.startsWith("saffron") }
        ?: rest.firstOrNull { it.contains("error", ignoreCase = true) }
        ?: rest.firstOrNull()
        ?: lines.lastOrNull().orEmpty()
}

/** Whether a runner version ("0.9.1", "0.10.0-beta.1") is at least major.minor.patch. */
fun runnerAtLeast(version: String, major: Int, minor: Int, patch: Int): Boolean {
    val parts = version.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    val have = listOf(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    val want = listOf(major, minor, patch)
    for (i in 0..2) if (have[i] != want[i]) return have[i] > want[i]
    return true
}
