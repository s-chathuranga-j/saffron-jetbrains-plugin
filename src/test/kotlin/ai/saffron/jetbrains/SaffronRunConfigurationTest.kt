package ai.saffron.jetbrains

import ai.saffron.jetbrains.run.SaffronCommand
import ai.saffron.jetbrains.run.SaffronConfigurationType
import ai.saffron.jetbrains.run.SaffronRunConfiguration
import ai.saffron.jetbrains.ui.SaffronProjectScan
import com.intellij.execution.PsiLocation
import com.intellij.execution.Location
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

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
        c.command = "accept"
        assertEquals(listOf("accept", "--all"), SaffronCommand.arguments(c))
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
}
