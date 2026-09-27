package ai.saffron.jetbrains

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronConfigurationType
import ai.saffron.jetbrains.run.SaffronRunConfiguration
import ai.saffron.jetbrains.run.SaffronSettingsEditor
import ai.saffron.jetbrains.ui.SaffronProjectScan
import ai.saffron.jetbrains.ui.ProjectStatus
import ai.saffron.jetbrains.ui.StatusProposal
import com.google.gson.Gson
import com.intellij.execution.PsiLocation
import com.intellij.execution.Location
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import javax.swing.JLabel

class SaffronRunConfigurationTest : BasePlatformTestCase() {

    private fun newConfiguration(name: String = "t"): SaffronRunConfiguration {
        val type = SaffronConfigurationType.INSTANCE
        val settings = RunManager.getInstance(project).createConfiguration(name, type.factory)
        return settings.configuration as SaffronRunConfiguration
    }

    fun `test the Saffron configuration type is registered`() {
        val type = ConfigurationTypeUtil.findConfigurationType(SaffronConfigurationType::class.java)
        assertEquals("Saffron", type.displayName)
        assertTrue(type.factory.createTemplateConfiguration(project) is SaffronRunConfiguration)
    }

    fun `test a path with a space stays one argument`() {
        val c = newConfiguration()
        c.command = "run"
        // What the plugin writes for a ticked or right-clicked file.
        c.paths = SaffronCommand.joinPaths(listOf("features/order checkout.saffron", "features/login.saffron"))
        assertEquals(
            listOf("run", "features/order checkout.saffron", "features/login.saffron"),
            SaffronCommand.arguments(c),
        )
        // What a person types: quotes keep the path whole, a comma inside a name survives.
        c.paths = "\"features/order checkout.saffron\" features/a,b.saffron"
        assertEquals(
            listOf("run", "features/order checkout.saffron", "features/a,b.saffron"),
            SaffronCommand.arguments(c),
        )
    }

    fun `test run arguments follow the CLI flags`() {
        val c = newConfiguration()
        c.command = "run"
        c.paths = "features/login.saffron, features/checkout"
        c.tags = "@smoke"
        c.replayOnly = true
        c.headed = true
        c.rerecord = true
        c.extraArgs = "--strict --workers 4"
        assertEquals(
            listOf("run", "features/login.saffron", "features/checkout", "--filter", "@smoke", "--no-agent", "--headed", "--rerecord", "--strict", "--workers", "4"),
            SaffronCommand.arguments(c),
        )
        assertEquals("saffron run features/login.saffron features/checkout --filter @smoke --no-agent --headed --rerecord --strict --workers 4", c.suggestedName())
    }

    fun `test report and accept ignore the run-only fields`() {
        val c = newConfiguration()
        c.command = "report"; c.paths = "x"; c.tags = "@y"; c.replayOnly = true
        assertEquals(listOf("report"), SaffronCommand.arguments(c))
        c.command = "accept"; c.paths = ""
        assertEquals(listOf("accept", "--all"), SaffronCommand.arguments(c))
        c.command = "accept"; c.paths = ".saffron/proposals/a/b.json .saffron/proposals/a/c.json"
        assertEquals(listOf("accept", ".saffron/proposals/a/b.json", ".saffron/proposals/a/c.json"), SaffronCommand.arguments(c))
        c.command = "reject"; c.paths = ".saffron/proposals/a/b.json"
        assertEquals(listOf("reject", ".saffron/proposals/a/b.json"), SaffronCommand.arguments(c))
        // Blank paths mean every proposal: only the explicit bulk action may do this.
        c.command = "reject"; c.paths = ""
        assertEquals(listOf("reject", "--all"), SaffronCommand.arguments(c))
    }

    fun `test trace sends one scenario name, with or without the quotes the field asks for`() {
        val c = newConfiguration()
        c.command = "trace"
        c.paths = ""
        assertEquals(listOf("trace"), SaffronCommand.arguments(c))
        // Quoted, as the tooltip says for a value with a space: the quotes are not sent along.
        c.paths = "\"features/a.saffron:Buy shoes\""
        assertEquals(listOf("trace", "features/a.saffron:Buy shoes"), SaffronCommand.arguments(c))
        // Unquoted, as people type anyway: still one name.
        c.paths = "features/a.saffron:Buy shoes"
        assertEquals(listOf("trace", "features/a.saffron:Buy shoes"), SaffronCommand.arguments(c))
        c.paths = "\"\""
        assertEquals(listOf("trace"), SaffronCommand.arguments(c))
        // Pasted as the runner lists it: the quotes in the name are the name's.
        val name = "features/a.saffron:Search for \"red  shoes\""
        c.paths = name
        assertEquals(listOf("trace", name), SaffronCommand.arguments(c))
        c.paths = "Say \"hi\""
        assertEquals(listOf("trace", "Say \"hi\""), SaffronCommand.arguments(c))
        // Wrapped whole, with the inner quotes escaped: unquoted once.
        c.paths = SaffronCommand.joinPaths(listOf(name))
        assertEquals(listOf("trace", name), SaffronCommand.arguments(c))
        // What Open Replay writes, feature:scenario as it is, arrives as it is.
        for (written in listOf(name, "features/a.saffron:Say\"hi\"", "features/my checkout.saffron:Buy shoes")) {
            c.paths = written
            assertEquals(listOf("trace", written), SaffronCommand.arguments(c))
        }
        // The run-only fields stay out of it.
        c.paths = name
        c.tags = "@smoke"; c.replayOnly = true; c.headed = true
        assertEquals(listOf("trace", name), SaffronCommand.arguments(c))
    }

    fun `test an unknown command is told every command there is`() {
        val c = newConfiguration()
        c.command = "bogus"
        assertThrows(
            RuntimeConfigurationError::class.java,
            "Unknown Saffron command \"bogus\"; use run, report, trace, accept, reject or prune",
            ThrowableRunnable<Throwable> { c.checkConfiguration() },
        )
    }

    fun `test the paths field is named for what the command reads from it`() {
        val editor = SaffronSettingsEditor()
        Disposer.register(testRootDisposable, editor)
        val c = newConfiguration()
        fun labels() = UIUtil.findComponentsOfType(editor.component, JLabel::class.java).map { it.text }
        c.command = "trace"
        editor.resetFrom(c)
        assertTrue(labels().toString(), "Scenario:" in labels())
        c.command = "reject"
        editor.resetFrom(c)
        assertTrue(labels().toString(), "Proposal files:" in labels())
        c.command = "run"
        editor.resetFrom(c)
        assertTrue(labels().toString(), "Files or folders:" in labels())
    }

    fun `test the command line runs in the project directory with the saffron binary`() {
        val c = newConfiguration()
        c.command = "run"
        val line = SaffronCommand.forConfiguration(c)
        assertEquals(project.basePath, line.workDirectory?.path)
        val all = listOf(line.exePath) + line.parametersList.parameters
        // either "<node_modules/.bin/saffron> run" or "npx -y -p saffron-ai saffron run"
        assertTrue(all.toString(), all.takeLast(2).let { it[0].endsWith("saffron") && it[1] == "run" })
    }

    fun `test right-clicking a saffron file offers a run configuration for it`() {
        val file = myFixture.configureByText("login.saffron", "Feature: Login\n\nScenario: Works\n    Given I am on the login page\n")
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_FILE, file)
            .add(Location.DATA_KEY, PsiLocation(file))
            .build()
        val context = ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
        val fromContext = context.configurationsFromContext ?: emptyList()
        val saffron = fromContext.mapNotNull { it.configuration as? SaffronRunConfiguration }.singleOrNull()
        assertNotNull("no Saffron configuration produced from a .saffron file context: $fromContext", saffron)
        assertEquals("run", saffron!!.command)
        assertTrue(saffron.paths, saffron.paths.endsWith("login.saffron"))
        assertEquals("Run login.saffron", saffron.name)
    }

    fun `test a plain text file offers no Saffron configuration`() {
        val file = myFixture.configureByText("notes.txt", "hello")
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_FILE, file)
            .add(Location.DATA_KEY, PsiLocation(file))
            .build()
        val context = ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
        val saffron = (context.configurationsFromContext ?: emptyList()).mapNotNull { it.configuration as? SaffronRunConfiguration }
        assertTrue(saffron.isEmpty())
    }

    fun `test the project scan lists feature files with scenario counts and the last run`() {
        val dir = Files.createTempDirectory("saffron-scan")
        Files.createDirectories(dir.resolve("features"))
        Files.createDirectories(dir.resolve("node_modules/saffron-ai"))
        Files.createDirectories(dir.resolve("node_modules/other/features"))
        Files.writeString(dir.resolve("features/login.saffron"), "Feature: L\n\nScenario: A\n  Given x\n\nScenario Outline: B\n  Given <y>\n")
        Files.writeString(dir.resolve("features/shared.steps.saffron"), "Feature: S\n\nStepSet: Log in\n  Given x\n")
        Files.writeString(dir.resolve("node_modules/other/features/ignored.saffron"), "Scenario: nope\n")
        Files.createDirectories(dir.resolve(".saffron/reports"))
        Files.createDirectories(dir.resolve(".saffron/proposals/login-saffron"))
        Files.writeString(dir.resolve(".saffron/proposals/login-saffron/a.json"), "{}")
        Files.writeString(dir.resolve(".saffron/reports/latest.html"), "<html></html>")
        Files.writeString(
            dir.resolve(".saffron/reports/latest.json"),
            """{"tool":"saffron","finishedAt":"2026-09-16T10:00:00.000Z","totals":{"scenarios":3,"green":2,"yellow":1,"red":0,"costUsd":1.5}}""",
        )
        assertTrue(SaffronProjectScan.isSaffronProject(dir.toString()))
        val state = SaffronProjectScan.scan(dir.toString())
        assertEquals(listOf("features/login.saffron", "features/shared.steps.saffron"), state.files.map { it.relativePath })
        assertEquals(2, state.files[0].scenarios)
        assertTrue(state.files[1].isLibrary)
        assertEquals(2, state.lastRun!!.green)
        assertEquals(1, state.lastRun!!.yellow)
        assertEquals(1.5, state.lastRun!!.costUsd)
        assertEquals(1, state.pendingProposals)
        assertTrue(state.packageInstalled)
        assertTrue(state.hasReport)
    }

    fun `test the status JSON from the runner maps onto the data classes`() {
        val digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val json = """{"tool":"saffron","version":"0.5.4","packageInstalled":true,
          "config":{"file":"saffron.config.json","effective":{"baseURL":"http://x","retries":2}},
          "features":[{"path":"features/login.saffron","name":"Login","stepSets":1,
            "scenarios":[{"name":"Successful login","line":7,"tags":["@smoke"],"outline":false,"rows":1,"cached":true,"proposal":false,"lastStatus":"green"}]}],
          "tags":[{"tag":"@smoke","scenarios":1}],
          "proposals":[{"file":".saffron/proposals/login-saffron/login-errors.json","feature":"features/login.saffron","scenario":"Login errors","mode":"record","createdAt":"t","verified":true,"adaptations":[],"narrative":"n","aiCalls":5,"costUsd":1.5,"revision":"$digest"}],
          "lastRun":{"startedAt":"a","finishedAt":"b","totals":{"scenarios":1,"green":1,"yellow":0,"red":0,"aiCalls":0,"costUsd":0,"plan":{"subscriptionType":"max","fiveHourBefore":18,"fiveHourAfter":20}},"reportHtml":".saffron/reports/latest.html",
            "attention":[{"feature":"features/login.saffron","scenario":"Login errors","baseScenario":"Login errors","status":"red","error":"Timed out","failedStep":"I should see the error",
              "evidence":[{"kind":"trace","file":".saffron/artifacts/login-saffron/login-errors/trace.zip"},
                {"kind":"failure","file":".saffron/artifacts/login-saffron/login-errors/failure.jpg","step":"I should see the error","sha256":"$digest"}]}]},
          "history":[{"startedAt":"a","green":1,"yellow":0,"red":0,"aiCalls":0,"costUsd":0}],
          "vocabulary":{"steps":3,"recorded":2,"unrecorded":1,"stepSets":1,"divergent":["x"],"duplicateWordings":[{"steps":["p","q"],"actions":"goto /"}]}}"""
        val s = Gson().fromJson(json, ProjectStatus::class.java)
        assertEquals("0.5.4", s.version)
        assertEquals("Login", s.features[0].name)
        assertEquals(7, s.features[0].scenarios[0].line)
        assertEquals("green", s.features[0].scenarios[0].lastStatus)
        assertEquals(1, s.proposals.size)
        assertEquals(true, s.proposals[0].verified)
        assertEquals(digest, s.proposals[0].revision)
        assertEquals(20.0, s.lastRun!!.totals.plan!!.fiveHourAfter)
        assertEquals("x", s.vocabulary.divergent[0])
        assertEquals(listOf("p", "q"), s.vocabulary.duplicateWordings[0].steps)
        assertEquals("http://x", s.config.effective.get("baseURL").asString)
        val attention = s.lastRun.attention!!.single()
        assertEquals("I should see the error", attention.failedStep)
        assertEquals(listOf("trace", "failure"), attention.evidence.map { it.kind })
        assertNull(attention.evidence[0].sha256)
        // The screenshot check trusts a picture with no digest, so a renamed
        // field would switch it off without anything else failing.
        assertEquals(digest, attention.evidence[1].sha256)
        assertEquals("I should see the error", attention.evidence[1].step)
    }

    fun `test Accept and Reject Selected name each proposal by the revision the tab showed`() {
        val digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val shown = StatusProposal(file = ".saffron/proposals/login-saffron/sign-in.json", revision = digest)
        // An older runner's status has no revision: the plain file, as before.
        val older = StatusProposal(file = ".saffron/proposals/login-saffron/sign-out.json")
        val c = newConfiguration()
        for (command in listOf("accept", "reject")) {
            c.command = command
            c.paths = SaffronCommand.joinPaths(listOf(shown, older).map { it.target })
            assertEquals(
                listOf(command, ".saffron/proposals/login-saffron/sign-in.json#$digest", ".saffron/proposals/login-saffron/sign-out.json"),
                SaffronCommand.arguments(c),
            )
        }
    }
}
