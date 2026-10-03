package ai.saffron.jetbrains

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronConfigurationType
import ai.saffron.jetbrains.run.SaffronRunConfiguration
import ai.saffron.jetbrains.run.SaffronRunner
import ai.saffron.jetbrains.run.SaffronSettingsEditor
import ai.saffron.jetbrains.ui.SaffronProjectScan
import ai.saffron.jetbrains.ui.SaffronRoots
import ai.saffron.jetbrains.ui.SaffronStatusService
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
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.Path
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
            "Unknown Saffron command \"bogus\"; use run, report, trace, accept, reject, prune or login",
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

    /** The Saffron configurations the run menu offers for [target] (a file or folder on disk). */
    private fun offered(target: Path): List<SaffronRunConfiguration> {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)!!
        // Files made on disk are indexed, and during indexing the menu offers no Saffron run.
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val psi = PsiManager.getInstance(project).let { if (vf.isDirectory) it.findDirectory(vf)!! else it.findFile(vf)!! }
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(Location.DATA_KEY, PsiLocation(psi))
            .build()
        val context = ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
        return (context.configurationsFromContext ?: emptyList()).mapNotNull { it.configuration as? SaffronRunConfiguration }
    }

    fun `test right-clicking a saffron file offers a run configuration for it`() {
        val base = Path.of(project.basePath!!)
        try {
            Files.createDirectories(base.resolve("features"))
            Files.writeString(base.resolve("saffron.config.json"), "{}")
            Files.writeString(base.resolve("features/login.saffron"), "Feature: Login\n\nScenario: Works\n    Given I am on the login page\n")
            val saffron = offered(base.resolve("features/login.saffron")).single()
            assertEquals("run", saffron.command)
            assertEquals("features/login.saffron", saffron.paths)
            assertEquals("Run login.saffron", saffron.name)
        } finally {
            Files.deleteIfExists(base.resolve("saffron.config.json"))
            base.resolve("features").toFile().deleteRecursively()
        }
    }

    fun `test a feature file outside any Saffron project offers no Saffron configuration`() {
        // Cucumber-JVM, SpecFlow, Behat: .feature files, no Saffron.
        val base = Path.of(project.basePath!!)
        try {
            Files.createDirectories(base.resolve("features"))
            Files.writeString(base.resolve("features/cart.feature"), "Feature: Cart\n\nScenario: Add\n    Given x\n")
            SaffronStatusService.getInstance(project).discoverRoots()
            assertEmpty(offered(base.resolve("features/cart.feature")))
            assertEmpty(offered(base.resolve("features")))
        } finally {
            base.resolve("features").toFile().deleteRecursively()
            SaffronStatusService.getInstance(project).discoverRoots()
        }
    }

    fun `test a folder holding another Saffron project is not run from the outer one`() {
        val base = Path.of(project.basePath!!)
        try {
            Files.writeString(base.resolve("saffron.config.json"), "{}")
            Files.createDirectories(base.resolve("apps/web/features"))
            Files.writeString(base.resolve("apps/web/saffron.config.json"), "{}")
            Files.writeString(base.resolve("apps/web/features/a.saffron"), "Feature: A\n\nScenario: A\n    Given x\n")
            SaffronStatusService.getInstance(project).discoverRoots()
            assertEmpty(offered(base.resolve("apps")))
            // The nested project itself still runs, from its own root.
            val web = offered(base.resolve("apps/web")).single()
            assertEquals(FileUtil.toSystemIndependentName(base.resolve("apps/web").toString()), web.workingDirectory)
            assertEquals("", web.paths)
            // And the outer scan leaves the nested project's files to it.
            assertEmpty(SaffronProjectScan.scan(base.toString()).files)
        } finally {
            Files.deleteIfExists(base.resolve("saffron.config.json"))
            base.resolve("apps").toFile().deleteRecursively()
            SaffronStatusService.getInstance(project).discoverRoots()
        }
    }

    fun `test a configuration saved with a blank working directory is found again, not duplicated`() {
        val base = Path.of(project.basePath!!)
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("Run login.saffron", SaffronConfigurationType.INSTANCE.factory)
        try {
            Files.createDirectories(base.resolve("features"))
            Files.writeString(base.resolve("saffron.config.json"), "{}")
            Files.writeString(base.resolve("features/login.saffron"), "Feature: Login\n\nScenario: Works\n    Given x\n")
            // What 0.3.2 wrote: no working directory, paths from the project directory.
            (settings.configuration as SaffronRunConfiguration).apply { command = "run"; workingDirectory = null; paths = "features/login.saffron" }
            runManager.addConfiguration(settings)
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve("features/login.saffron"))!!
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(Location.DATA_KEY, PsiLocation(PsiManager.getInstance(project).findFile(vf)!!))
                .build()
            assertSame(settings, ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN).findExisting())
        } finally {
            runManager.removeConfiguration(settings)
            Files.deleteIfExists(base.resolve("saffron.config.json"))
            base.resolve("features").toFile().deleteRecursively()
        }
    }

    fun `test a relative working directory is taken from the project directory and must exist`() {
        val base = Path.of(project.basePath!!)
        val c = newConfiguration()
        c.command = "run"
        c.workingDirectory = "apps/web"
        val expected = base.resolve("apps/web").toString()
        assertEquals(expected, c.resolvedWorkingDirectory)
        assertEquals(expected, SaffronCommand.forConfiguration(c).workDirectory?.path)
        assertThrows(RuntimeConfigurationError::class.java, "does not exist", ThrowableRunnable<Throwable> { c.checkConfiguration() })
        try {
            Files.createDirectories(base.resolve("apps/web"))
            c.checkConfiguration()
        } finally {
            base.resolve("apps").toFile().deleteRecursively()
        }
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
        Files.writeString(dir.resolve("features/cart.feature"), "Feature: C\n\nScenario: Add\n  Given x\n")
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
        assertEquals(listOf("features/cart.feature", "features/login.saffron", "features/shared.steps.saffron"), state.files.map { it.relativePath })
        assertEquals(1, state.files[0].scenarios)
        assertEquals(2, state.files[1].scenarios)
        assertTrue(state.files[2].isLibrary)
        assertEquals(2, state.lastRun!!.green)
        assertEquals(1, state.lastRun!!.yellow)
        assertEquals(1.5, state.lastRun!!.costUsd)
        assertEquals(1, state.pendingProposals)
        assertTrue(state.packageInstalled)
        assertTrue(state.hasReport)
    }

    /** What `saffron status --json` prints in 0.9.2: no cost from an unpriced provider, unreadable proposals, cache states, data. */
    private fun fixture(): ProjectStatus =
        Gson().fromJson(javaClass.getResource("/status-0.9.2.json")!!.readText(), ProjectStatus::class.java)

    fun `test the status JSON from the runner maps onto the data classes`() {
        val digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val s = fixture()
        assertEquals("0.9.2", s.version)
        assertEquals("Login", s.features[0].name)
        assertEquals(7, s.features[0].scenarios[0].line)
        assertEquals("green", s.features[0].scenarios[0].lastStatus)
        assertEquals(1, s.proposals.size)
        assertEquals(true, s.proposals[0].verified)
        assertEquals(digest, s.proposals[0].revision)
        assertEquals("the scenario changed since it was filed", s.proposals[0].stale)
        assertEquals(true, s.proposals[0].unbound)
        assertEquals(20.0, s.lastRun!!.totals.plan!!.fiveHourAfter)
        assertEquals("x", s.vocabulary.divergent[0])
        assertEquals(listOf("p", "q"), s.vocabulary.duplicateWordings[0].steps)
        assertEquals("http://x", s.config.effective.get("baseURL").asString)
        assertEquals("feature-gone", s.orphans!!.single().reason)
        val attention = s.lastRun.attention!!.single()
        assertEquals("I should see the error", attention.failedStep)
        assertEquals(listOf("trace", "failure"), attention.evidence.map { it.kind })
        assertNull(attention.evidence[0].sha256)
        // The screenshot check trusts a picture with no digest, so a renamed
        // field would switch it off without anything else failing.
        assertEquals(digest, attention.evidence[1].sha256)
        assertEquals("I should see the error", attention.evidence[1].step)
    }

    fun `test a cost the runner omits is not reported, never a measured zero`() {
        val s = fixture()
        assertNull(s.lastRun!!.totals.costUsd)
        assertNull(s.history.single().costUsd)
        assertNull(s.proposals.single().costUsd)
        assertEquals(3, s.lastRun.totals.aiCalls)
    }

    fun `test unreadable proposals are read with their reason`() {
        val broken = fixture().unreadableProposals.single()
        assertEquals(".saffron/proposals/login-saffron/broken.json", broken.file)
        assertEquals("Unexpected end of JSON input", broken.problem)
        // An older runner's status has no such list.
        assertEmpty(Gson().fromJson("{}", ProjectStatus::class.java).unreadableProposals)
    }

    fun `test a folder of plain Gherkin feature files is not a Saffron project`() {
        val top = Files.createTempDirectory("saffron-cucumber")
        try {
            Files.createDirectories(top.resolve("features"))
            Files.writeString(top.resolve("features/x.feature"), "Feature: X\n\nScenario: Y\n  Given z\n")
            assertFalse(SaffronProjectScan.isSaffronProject(top.toString()))
            assertEmpty(SaffronRoots.discover(top))
            Files.writeString(top.resolve("features/x.saffron"), "Feature: X\n\nExample: Y\n  Given z\n")
            assertTrue(SaffronProjectScan.isSaffronProject(top.toString()))
            // Example: is a scenario to the runner too.
            assertEquals(1, SaffronProjectScan.scan(top.toString()).files.single { it.relativePath == "features/x.saffron" }.scenarios)
        } finally {
            top.toFile().deleteRecursively()
        }
    }

    fun `test discover finds every nested project and falls back to the root`() {
        val top = Files.createTempDirectory("saffron-roots")
        try {
            // No config anywhere, no package, no features: nothing.
            assertEmpty(SaffronRoots.discover(top))
            // A plain project at the root, without a config: the root.
            Files.createDirectories(top.resolve("node_modules/saffron-ai"))
            assertEquals(listOf(top), SaffronRoots.discover(top))
            for (dir in listOf("apps/web", "apps/admin", "node_modules/pkg", "a/b/c/d/e")) {
                Files.createDirectories(top.resolve(dir))
                Files.writeString(top.resolve("$dir/saffron.config.json"), "{}")
            }
            // Skipped folders and folders below the depth limit are not searched.
            assertEquals(listOf(top.resolve("apps/admin"), top.resolve("apps/web")), SaffronRoots.discover(top))
            assertEquals(
                listOf(top.resolve("a/b/c/d/e"), top.resolve("apps/admin"), top.resolve("apps/web")),
                SaffronRoots.discover(top, maxDepth = 5),
            )
        } finally {
            top.toFile().deleteRecursively()
        }
    }

    fun `test rootFor walks up to the nearest config, never above the top`() {
        val top = Files.createTempDirectory("saffron-root-for")
        try {
            Files.createDirectories(top.resolve("apps/web/features/deep"))
            Files.writeString(top.resolve("apps/web/saffron.config.json"), "{}")
            assertEquals(top.resolve("apps/web"), SaffronRoots.rootFor(top.resolve("apps/web/features/deep/a.saffron"), top))
            assertEquals(top.resolve("apps/web"), SaffronRoots.rootFor(top.resolve("apps/web/saffron.config.json"), top))
            // No config on the way up: the top.
            assertEquals(top, SaffronRoots.rootFor(top.resolve("other/a.feature"), top))
            // A config above the top is not looked at.
            Files.writeString(top.resolve("saffron.config.json"), "{}")
            val inner = top.resolve("apps")
            assertEquals(inner, SaffronRoots.rootFor(inner.resolve("mobile/a.feature"), inner))
            assertEquals(inner, SaffronRoots.rootFor(top.resolve("elsewhere/a.feature"), inner))
        } finally {
            top.toFile().deleteRecursively()
        }
    }

    fun `test right-clicking a file in a nested project runs it from that project`() {
        val base = Path.of(project.basePath!!)
        val nested = base.resolve("apps/web")
        try {
            Files.createDirectories(nested.resolve("features"))
            Files.writeString(nested.resolve("saffron.config.json"), "{}")
            Files.writeString(nested.resolve("features/cart.feature"), "Feature: Cart\n\nScenario: Add\n    Given x\n")
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nested.resolve("features/cart.feature"))!!
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val file = PsiManager.getInstance(project).findFile(vf)!!
            val dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.PSI_FILE, file)
                .add(Location.DATA_KEY, PsiLocation(file))
                .build()
            val context = ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
            val saffron = (context.configurationsFromContext ?: emptyList()).mapNotNull { it.configuration as? SaffronRunConfiguration }.single()
            assertEquals(FileUtil.toSystemIndependentName(nested.toString()), saffron.workingDirectory)
            assertEquals("features/cart.feature", saffron.paths)
            assertEquals(saffron.workingDirectory, SaffronCommand.forConfiguration(saffron).workDirectory?.path?.let(FileUtil::toSystemIndependentName))
        } finally {
            base.resolve("apps").toFile().deleteRecursively()
        }
    }

    fun `test a reused tool window configuration keeps nothing from its last use`() {
        val name = "Saffron: reuse test"
        val runManager = RunManager.getInstance(project)
        try {
            val first = SaffronRunner.prepare(project, name) {
                it.rerecord = true; it.headed = true; it.tags = "@wip"; it.extraArgs = "--strict"; it.workingDirectory = "/elsewhere"
            }
            val second = SaffronRunner.prepare(project, name) { it.paths = "features/a.saffron" }
            assertSame(first, second)
            val c = second.configuration as SaffronRunConfiguration
            assertFalse(c.rerecord)
            assertFalse(c.headed)
            assertEquals("", c.tags)
            assertEquals("", c.extraArgs)
            assertEquals(SaffronStatusService.getInstance(project).root.toString(), c.workingDirectory)
            assertEquals(listOf("run", "features/a.saffron"), SaffronCommand.arguments(c))
        } finally {
            runManager.findConfigurationByTypeAndName(SaffronConfigurationType.INSTANCE, name)?.let(runManager::removeConfiguration)
        }
    }

    fun `test a tool window button never resets a saved configuration of the same name`() {
        val name = "Run login.saffron"
        val runManager = RunManager.getInstance(project)
        val type = SaffronConfigurationType.INSTANCE
        // What right-click, Run and then Save Configuration leave behind.
        val saved = runManager.createConfiguration(name, type.factory)
        runManager.addConfiguration(saved)
        assertFalse(saved.isTemporary)
        val mine = saved.configuration as SaffronRunConfiguration
        mine.paths = "features/login.saffron"
        mine.tags = "@smoke"
        mine.extraArgs = "--workers 2"
        try {
            // Run Selected with login.saffron ticked.
            val used = SaffronRunner.prepare(project, name) { it.paths = "features/login.saffron"; it.headed = true }
            assertNotSame(saved, used)
            assertTrue(used.isTemporary)
            assertEquals("Saffron: $name", used.name)
            assertSame(saved, runManager.findConfigurationByTypeAndName(type, name))
            assertEquals("@smoke", mine.tags)
            assertEquals("--workers 2", mine.extraArgs)
            assertFalse(mine.headed)
            // Pressed again: the same temporary one is reused.
            assertSame(used, SaffronRunner.prepare(project, name) { it.paths = "features/login.saffron" })
        } finally {
            listOf(name, "Saffron: $name").forEach { n -> runManager.findConfigurationByTypeAndName(type, n)?.let(runManager::removeConfiguration) }
        }
    }

    fun `test login passes the provider typed in the field`() {
        val c = newConfiguration()
        c.command = "login"
        c.paths = ""
        assertEquals(listOf("login"), SaffronCommand.arguments(c))
        c.paths = " codex "
        assertEquals(listOf("login", "codex"), SaffronCommand.arguments(c))
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
