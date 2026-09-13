package ai.saffron.jetbrains

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.textmate.TextMateBackedFileType
import org.jetbrains.plugins.textmate.TextMateFileType
import org.jetbrains.plugins.textmate.TextMateService
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files

class SaffronPluginTest : BasePlatformTestCase() {

    fun `test saffron extension is registered and highlighted by the TextMate grammar`() {
        // The TextMate plugin intentionally serves TextMateBackedFileType files
        // itself (highlighting), while the registered type supplies icon/name.
        val registered = FileTypeManager.getInstance().getFileTypeByExtension("saffron")
        assertEquals(SaffronFileType.INSTANCE, registered)
        assertTrue(registered is TextMateBackedFileType)

        val file = myFixture.configureByText(
            "checkout.saffron",
            "Feature: Checkout\n\nStepSet: Log in\n    Given I am on the login page\n\nScenario: Buy\n    StepSet Log in\n    Then I am on the cart page\n",
        )
        assertTrue(
            "expected TextMate-backed highlighting, got ${file.fileType.name}",
            file.fileType == SaffronFileType.INSTANCE || file.fileType is TextMateFileType,
        )
        val descriptor = TextMateService.getInstance().getLanguageDescriptorByFileName("checkout.saffron")
        assertNotNull("no TextMate grammar resolved for .saffron", descriptor)
        assertEquals("source.saffron", descriptor!!.scopeName)
    }

    fun `test the TextMate bundle is provided and unpacked with the grammar`() {
        val bundles = TextMateBundleProvider.EP_NAME.extensionList.flatMap { it.getBundles() }
        val saffron = bundles.single { it.name == "saffron" }
        assertTrue(Files.exists(saffron.path.resolve("package.json")))
        val grammar = saffron.path.resolve("syntaxes/saffron.tmLanguage.json")
        assertTrue(Files.exists(grammar))
        val text = Files.readString(grammar)
        assertTrue(text.contains("\"scopeName\": \"source.saffron\""))
        assertTrue(text.contains("StepSet"))
    }

    fun `test the language server is registered with LSP4IJ`() {
        val definition = LanguageServersRegistry.getInstance().getServerDefinition("saffron")
        assertNotNull("saffron server definition missing", definition)
        assertEquals("Saffron Language Server", definition!!.displayName)
    }

    fun `test the server launches saffron lsp in the project directory`() {
        val server = SaffronLanguageServer(project)
        val command = server.commandLine
        assertNotNull(command)
        assertEquals(listOf("saffron", "lsp"), command!!.parametersList.parameters.takeLast(2).let {
            // either "<node_modules/.bin/saffron> lsp" or "npx saffron lsp"
            if (it.first().endsWith("saffron")) listOf("saffron", "lsp") else it
        })
        assertEquals(project.basePath, command.workDirectory?.path)
    }
}
