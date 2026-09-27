package ai.saffron.jetbrains

import ai.saffron.jetbrains.ui.LastRunTab
import ai.saffron.jetbrains.ui.ProposalsTab
import ai.saffron.jetbrains.ui.SaffronStatusService
import ai.saffron.jetbrains.ui.StatusProposal
import ai.saffron.jetbrains.ui.checkedCopy
import ai.saffron.jetbrains.ui.currentEvidence
import ai.saffron.jetbrains.ui.evidenceCopies
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.textmate.TextMateBackedFileType
import org.jetbrains.plugins.textmate.TextMateFileType
import org.jetbrains.plugins.textmate.TextMateService
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

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

    private fun sha256(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun `test a Last Run screenshot opens only while it is the picture that run took`() {
        val shots = Files.createTempDirectory("saffron-shots").toFile()
        val root = Files.createTempDirectory("saffron-copies").toFile()
        val file = File(shots, "buy-shoes/failure.jpg").apply { parentFile.mkdirs() }
        try {
            file.writeText("run 1")
            val digest = sha256("run 1")
            assertNotNull(currentEvidence(file, digest))
            // A runner before digests: trusted as before.
            assertNotNull(currentEvidence(file, null))
            // Copies from two days ago go when a new one is made; this morning's stay.
            val now = System.currentTimeMillis()
            val old = File(root, "0123456789abcdef").apply { mkdirs(); File(this, "log-in-failure.jpg").writeText("old") }
            old.setLastModified(now - 2 * 24 * 60 * 60 * 1000L)
            val recent = File(root, "fedcba9876543210").apply { mkdirs() }
            recent.setLastModified(now - 60 * 60 * 1000L)
            // What the editor opens is a copy of the checked bytes, so a later
            // write to the original does not reach it.
            val copy = checkedCopy(currentEvidence(file, digest)!!, digest, file, root)
            file.writeText("run 2")
            // Every picture is failure.jpg: the copy says whose it is.
            assertEquals("buy-shoes-failure.jpg", copy.name)
            assertEquals("run 1", copy.readText())
            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertNull(currentEvidence(file, digest))
            file.delete()
            assertNull(currentEvidence(file, null))
            // Per user: the IDE's own system folder, not a temp folder every user of the machine shares.
            assertEquals(PathManager.getSystemDir().resolve("saffron-evidence").toFile(), evidenceCopies())
        } finally {
            shots.deleteRecursively()
            root.deleteRecursively()
        }
    }

    /**
     * The Last Run tab against a stand-in `saffron` that prints a status:
     * one row whose picture a later run replaced, one whose picture is its own.
     */
    fun `test Last Run names a refused screenshot, drops the message on another row, and opens a checked copy`() {
        // The stand-in below is a shell script.
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val made = listOf(base.resolve("node_modules"), base.resolve(".saffron"))
        val copies = mutableListOf<File>()
        // The test editor manager opens text only; the IDE's image viewer is not there.
        val types = FileTypeManager.getInstance()
        val imageType = types.getFileTypeByExtension("jpg")
        ApplicationManager.getApplication().runWriteAction { types.associateExtension(PlainTextFileType.INSTANCE, "jpg") }
        try {
            val artifacts = base.resolve(".saffron/artifacts/checkout-saffron")
            Files.createDirectories(artifacts.resolve("buy-shoes"))
            Files.createDirectories(artifacts.resolve("log-in"))
            Files.writeString(artifacts.resolve("buy-shoes/failure.jpg"), "a later run")
            Files.writeString(artifacts.resolve("log-in/failure.jpg"), "log in")
            fun row(scenario: String, slug: String, picture: String) =
                """{"feature":"features/checkout.saffron","scenario":"$scenario","baseScenario":"$scenario","status":"red",""" +
                    """"evidence":[{"kind":"failure","file":".saffron/artifacts/checkout-saffron/$slug/failure.jpg","sha256":"${sha256(picture)}"}]}"""
            val status = base.resolve(".saffron/status.json")
            Files.writeString(
                status,
                """{"packageInstalled":false,"lastRun":{"totals":{"scenarios":2,"red":2},"attention":[""" +
                    row("Buy shoes", "buy-shoes", "this run") + "," + row("Log in", "log-in", "log in") + "]}}",
            )
            val bin = base.resolve("node_modules/.bin/saffron")
            Files.createDirectories(bin.parent)
            Files.writeString(bin, "#!/bin/sh\ncat '$status'\n")
            bin.toFile().setExecutable(true)

            val tab = LastRunTab(project, testRootDisposable)
            val tree = UIUtil.findComponentOfType(tab, Tree::class.java)!!
            fun rows() = (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList()
                .map { it as DefaultMutableTreeNode }.filter { it.level == 2 }
            PlatformTestUtil.waitWithEventsDispatching("the status never loaded", { rows().size == 2 }, 60)
            val installHint = "saffron-ai is not installed here (npm i -D saffron-ai); status came from npx."
            val note = UIUtil.findComponentsOfType(tab, JBLabel::class.java).single { it.text == installHint }
            val toolbar = UIUtil.findComponentOfType(tab.toolbar as JComponent, ActionToolbarImpl::class.java)!!
            val openScreenshot = (toolbar.actionGroup as DefaultActionGroup).childActionsOrStubs.single { it.templateText == "Open Screenshot" }
            fun select(scenario: String) {
                tree.selectionPath = TreePath(rows().single { it.toString() == scenario }.path)
            }
            fun open(scenario: String) {
                select(scenario)
                openScreenshot.actionPerformed(TestActionEvent.createTestEvent(openScreenshot))
            }

            open("Buy shoes")
            // The picture is read and checked off the UI thread: nothing is decided yet.
            assertEquals(installHint, note.text)
            PlatformTestUtil.waitWithEventsDispatching("no refusal", { note.text != installHint }, 30)
            assertEquals("A later run replaced the screenshot of \"Buy shoes\". Refresh to see its results.", note.text)
            assertEmpty(FileEditorManager.getInstance(project).openFiles)

            // Another row: the refusal was about Buy shoes, so it goes, and the status's own note comes back.
            select("Log in")
            assertEquals(installHint, note.text)
            open("Log in")
            val editors = FileEditorManager.getInstance(project)
            PlatformTestUtil.waitWithEventsDispatching("the screenshot never opened", { editors.openFiles.isNotEmpty() }, 30)
            val shown = editors.openFiles.single()
            copies += File(shown.path).parentFile
            // The checked copy, named after its scenario, in the IDE's own folder.
            assertEquals("log-in-failure.jpg", shown.name)
            assertTrue(shown.path, shown.path.startsWith(evidenceCopies().path))
            assertEquals("log in", String(shown.contentsToByteArray()))
            assertEquals(installHint, note.text)
            editors.closeFile(shown)
        } finally {
            ApplicationManager.getApplication().runWriteAction {
                types.removeAssociatedExtension(PlainTextFileType.INSTANCE, "jpg")
                if (imageType != UnknownFileType.INSTANCE) types.associateExtension(imageType, "jpg")
            }
            made.forEach { it.toFile().deleteRecursively() }
            copies.forEach { it.deleteRecursively() }
        }
    }

    /**
     * The Proposals tab against a stand-in `saffron` whose status a run
     * rewrites: a tick survives a refresh, but not a new proposal filed
     * under the same name, which Accept Selected would decide unseen.
     */
    fun `test a ticked proposal comes back unticked once a later run replaced it`() {
        // The stand-in below is a shell script.
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val made = listOf(base.resolve("node_modules"), base.resolve(".saffron"))
        try {
            val status = base.resolve(".saffron/status.json")
            Files.createDirectories(status.parent)
            fun listed(revision: String) = Files.writeString(
                status,
                """{"packageInstalled":true,"proposals":[{"file":".saffron/proposals/login-saffron/sign-in.json",""" +
                    """"feature":"features/login.saffron","scenario":"Sign in","revision":"$revision"}]}""",
            )
            listed("a".repeat(64))
            val bin = base.resolve("node_modules/.bin/saffron")
            Files.createDirectories(bin.parent)
            Files.writeString(bin, "#!/bin/sh\ncat '$status'\n")
            bin.toFile().setExecutable(true)

            val tab = ProposalsTab(project, testRootDisposable)
            @Suppress("UNCHECKED_CAST")
            val list = UIUtil.findComponentOfType(tab, CheckBoxList::class.java) as CheckBoxList<StatusProposal>
            fun row() = if (list.itemsCount == 1) list.getItemAt(0) else null
            PlatformTestUtil.waitWithEventsDispatching("the status never loaded", { row()?.revision == "a".repeat(64) }, 60)
            val ticked = row()!!
            list.setItemSelected(ticked, true)

            // The same proposal, listed again: still ticked.
            SaffronStatusService.getInstance(project).refresh()
            PlatformTestUtil.waitWithEventsDispatching("the status never reloaded", { row().let { it != null && it !== ticked } }, 60)
            assertTrue(list.isItemSelected(row()!!))

            // A run files a new proposal under the same name.
            listed("b".repeat(64))
            SaffronStatusService.getInstance(project).refresh()
            PlatformTestUtil.waitWithEventsDispatching("the replacement never loaded", { row()?.revision == "b".repeat(64) }, 60)
            assertFalse(list.isItemSelected(row()!!))
        } finally {
            made.forEach { it.toFile().deleteRecursively() }
        }
    }
}
