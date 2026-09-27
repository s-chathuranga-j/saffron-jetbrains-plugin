package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
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
)

class StatusTotals(
    val scenarios: Int = 0,
    val green: Int = 0,
    val yellow: Int = 0,
    val red: Int = 0,
    val aiCalls: Int = 0,
    val costUsd: Double = 0.0,
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

class StatusHistoryRun(val startedAt: String = "", val green: Int = 0, val yellow: Int = 0, val red: Int = 0, val aiCalls: Int = 0, val costUsd: Double = 0.0)

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

class StatusConfig(val file: String? = null, val effective: JsonObject = JsonObject())

class ProjectStatus(
    val version: String = "",
    val packageInstalled: Boolean = false,
    val config: StatusConfig = StatusConfig(),
    val features: List<StatusFeature> = emptyList(),
    val tags: List<StatusTag> = emptyList(),
    val proposals: List<StatusProposal> = emptyList(),
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
            val result = runStatus(project.basePath)
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
        val TOPIC: Topic<StatusListener> = Topic.create("Saffron status", StatusListener::class.java)

        fun getInstance(project: Project): SaffronStatusService = project.getService(SaffronStatusService::class.java)

        /** `saffron status --json` for a project directory; never throws. */
        fun runStatus(basePath: String?): StatusLoad {
            basePath ?: return StatusLoad(null, "no project directory")
            return try {
                val command: GeneralCommandLine = SaffronCommand.base(basePath).withParameters("status", "--json")
                val output = ExecUtil.execAndGetOutput(command, 60_000)
                if (output.exitCode != 0 || output.stdout.isBlank()) {
                    StatusLoad(null, output.stderr.ifBlank { "saffron status exited with ${output.exitCode}" }.trim().lines().last())
                } else {
                    StatusLoad(Gson().fromJson(output.stdout, ProjectStatus::class.java), null)
                }
            } catch (e: Exception) {
                StatusLoad(null, e.message ?: e.toString())
            }
        }
    }
}
