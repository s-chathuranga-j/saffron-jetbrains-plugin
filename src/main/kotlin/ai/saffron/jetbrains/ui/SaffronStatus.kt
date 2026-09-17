package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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

class StatusLastRun(val startedAt: String = "", val finishedAt: String = "", val totals: StatusTotals = StatusTotals(), val reportHtml: String? = null)

class StatusHistoryRun(val startedAt: String = "", val green: Int = 0, val yellow: Int = 0, val red: Int = 0, val aiCalls: Int = 0, val costUsd: Double = 0.0)

class NearDuplicate(val a: String = "", val b: String = "", val similarity: Double = 0.0)

class StatusVocabulary(
    val steps: Int = 0,
    val recorded: Int = 0,
    val unrecorded: Int = 0,
    val stepSets: Int = 0,
    val divergent: List<String> = emptyList(),
    val nearDuplicates: List<NearDuplicate> = emptyList(),
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
