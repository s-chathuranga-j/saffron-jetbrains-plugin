package ai.saffron.jetbrains

import ai.saffron.jetbrains.ui.CiRunsTab
import ai.saffron.jetbrains.ui.LastRunTab
import ai.saffron.jetbrains.ui.RunNode
import ai.saffron.jetbrains.ui.ProposalsTab
import ai.saffron.jetbrains.ui.SaffronStatusService
import ai.saffron.jetbrains.ui.StatusAttention
import ai.saffron.jetbrains.ui.StatusProposal
import ai.saffron.jetbrains.ui.attentionTooltip
import ai.saffron.jetbrains.ui.checkedCopy
import ai.saffron.jetbrains.ui.currentEvidence
import ai.saffron.jetbrains.ui.evidenceCopies
import ai.saffron.jetbrains.ui.ago
import ai.saffron.jetbrains.ui.runnerError
import ai.saffron.jetbrains.ui.tooltipHtml
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
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
import java.time.Instant
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

    /**
     * The CI Runs tab against a stand-in `saffron`: the runs `saffron runs`
     * prints, the pending count the status gives, an import whose outcome
     * lands under its run, and the Proposals tab naming the run a proposal
     * came from.
     */
    fun `test CI Runs lists saffron runs and imports one with saffron import --run`() {
        // The stand-in below is a shell script.
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val made = listOf(base.resolve("node_modules"), base.resolve(".saffron"))
        try {
            val dir = base.resolve(".saffron")
            Files.createDirectories(dir)
            val runs = dir.resolve("runs.json")
            Files.writeString(
                runs,
                """{"provider":"azure","branch":"feature/login","runs":[""" +
                    """{"id":"41","name":"e2e","number":"20261001.41","title":"<html><img src=https://example.com/x.png>","status":"completed","conclusion":"failure","branch":"feature/login","commit":"abc1234","url":"https://dev.azure.com/acme/shop/_build/results?buildId=41","pending":0},""" +
                    """{"id":"40","name":"e2e","number":"20261001.40","status":"in_progress","pending":0}]}""",
            )
            val imported = dir.resolve("import.json")
            Files.writeString(
                imported,
                """{"warnings":["this checkout is 1 commit behind"],"proposals":[""" +
                    """{"file":".saffron/proposals/login-saffron/sign-in.json","feature":"features/login.saffron","scenario":"Sign in","outcome":"imported"},""" +
                    """{"file":".saffron/proposals/cart-saffron/add.json","feature":"features/cart.saffron","scenario":"Add to cart","outcome":"refused","reason":"does not fit this checkout"}]}""",
            )
            val status = dir.resolve("status.json")
            fun proposal(name: String) =
                """{"file":".saffron/proposals/login-saffron/$name.json","feature":"features/login.saffron",""" +
                    """"scenario":"Sign in","mode":"heal","verified":true,"revision":"${"a".repeat(64)}",""" +
                    """"importedFrom":{"run":"azure:41","url":"https://dev.azure.com/acme/shop/_build/results?buildId=41","branch":"feature/login","commit":"abc1234def"}}"""
            fun proposals(vararg names: String) =
                Files.writeString(status, """{"packageInstalled":true,"proposals":[${names.joinToString(",") { proposal(it) }}]}""")
            proposals("sign-in")
            val log = dir.resolve("calls.log")
            val bin = base.resolve("node_modules/.bin/saffron")
            Files.createDirectories(bin.parent)
            Files.writeString(bin, "#!/bin/sh\necho \"\$*\" >> '$log'\ncase \"\$1\" in\n  runs) cat '$runs' ;;\n  import) cat '$imported'; exit 1 ;;\n  *) cat '$status' ;;\nesac\n")
            bin.toFile().setExecutable(true)

            val tab = CiRunsTab(project, testRootDisposable)
            val tree = UIUtil.findComponentOfType(tab, Tree::class.java)!!
            fun runRows() = (0 until tree.model.getChildCount(tree.model.root)).map { tree.model.getChild(tree.model.root, it) as DefaultMutableTreeNode }
            fun detail(node: DefaultMutableTreeNode) = (node.userObject as RunNode).detail
            PlatformTestUtil.waitWithEventsDispatching("the runs never loaded", { runRows().size == 2 }, 60)
            assertEquals(listOf("e2e #20261001.41", "e2e #20261001.40"), runRows().map { it.toString() })
            // The status's notes count the run's proposal pending here.
            PlatformTestUtil.waitWithEventsDispatching("the status never counted", { detail(runRows()[0]).contains("1 proposal pending here") }, 60)
            assertTrue(detail(runRows()[0]), detail(runRows()[0]).startsWith("failure"))
            assertTrue(detail(runRows()[1]), detail(runRows()[1]).startsWith("in progress"))
            // The CI's title is text in the tooltip, never markup.
            val tip = (runRows()[0].userObject as RunNode).tooltip!!
            assertTrue(tip, tip.startsWith("<html>&lt;html&gt;&lt;img src=https://example.com/x.png&gt;<br>"))
            assertFalse(tip, tip.contains("<img"))

            tree.selectionPath = TreePath(runRows()[0].path)
            val toolbar = UIUtil.findComponentOfType(tab.toolbar as JComponent, ActionToolbarImpl::class.java)!!
            val importRun = (toolbar.actionGroup as DefaultActionGroup).childActionsOrStubs.single { it.templateText == "Import Run" }
            importRun.actionPerformed(TestActionEvent.createTestEvent(importRun))
            PlatformTestUtil.waitWithEventsDispatching("the import never finished", { runRows()[0].childCount == 3 }, 60)
            assertTrue(Files.readString(log).lines().contains("import --run 41 --json"))
            val outcomes = (0 until 3).map { runRows()[0].getChildAt(it) as DefaultMutableTreeNode }
            assertEquals(listOf("this checkout is 1 commit behind", "login.saffron › Sign in", "cart.saffron › Add to cart"), outcomes.map { it.toString() })
            assertEquals("not imported: does not fit this checkout", detail(outcomes[2]))
            val note = UIUtil.findComponentsOfType(tab, JBLabel::class.java).single()
            val summary = "1 proposal from e2e #20261001.41 ready for review in Proposals, 1 not imported, 1 warning."
            PlatformTestUtil.waitWithEventsDispatching("no summary", { note.text == summary }, 30)
            // A status reload keeps the rows, the selected run and the summary.
            proposals("sign-in", "sign-up")
            SaffronStatusService.getInstance(project).refresh()
            PlatformTestUtil.waitWithEventsDispatching("the rows went with the reload", { runRows()[0].childCount == 3 && detail(runRows()[0]).contains("2 proposals pending here") }, 60)
            assertEquals(TreePath(runRows()[0].path), tree.selectionPath)
            assertEquals(summary, note.text)
            proposals("sign-in")

            // The same run again: nothing new.
            Files.writeString(imported, """{"proposals":[{"file":".saffron/proposals/login-saffron/sign-in.json","feature":"features/login.saffron","scenario":"Sign in","outcome":"unchanged"}]}""")
            importRun.actionPerformed(TestActionEvent.createTestEvent(importRun))
            PlatformTestUtil.waitWithEventsDispatching("no re-import summary", { note.text == "Already imported: nothing changed for e2e #20261001.41." }, 60)
            // A refusal: the reason, under the run and in the note.
            Files.writeString(imported, """{"error":"the bundle is from another repository"}""")
            importRun.actionPerformed(TestActionEvent.createTestEvent(importRun))
            PlatformTestUtil.waitWithEventsDispatching("no refusal", { note.text == "e2e #20261001.41 was not imported: the bundle is from another repository" }, 60)
            assertEquals(listOf("the bundle is from another repository"), (0 until runRows()[0].childCount).map { runRows()[0].getChildAt(it).toString() })

            // Proposals names the run each imported proposal came from.
            val proposals = ProposalsTab(project, testRootDisposable)
            @Suppress("UNCHECKED_CAST")
            val list = UIUtil.findComponentOfType(proposals, CheckBoxList::class.java) as CheckBoxList<StatusProposal>
            PlatformTestUtil.waitWithEventsDispatching("the proposals never loaded", { list.itemsCount == 1 }, 60)
            assertTrue(list.model.getElementAt(0).text, list.model.getElementAt(0).text.endsWith("heal · verified · from run 41"))
            val from = list.getItemAt(0)!!.importedFrom!!
            assertEquals("imported from run azure:41 (https://dev.azure.com/acme/shop/_build/results?buildId=41) · on feature/login @ abc1234", from.description)
        } finally {
            made.forEach { it.toFile().deleteRecursively() }
        }
    }

    /** A stand-in `saffron` in [dir]: `runs` runs [runs] (shell), anything else prints a bare status. */
    private fun standIn(dir: Path, runs: String) {
        val bin = dir.resolve("node_modules/.bin/saffron")
        Files.createDirectories(bin.parent)
        Files.writeString(bin, "#!/bin/sh\ncase \"\$1\" in\n  runs) $runs ;;\n  *) echo '{\"packageInstalled\":true}' ;;\nesac\n")
        bin.toFile().setExecutable(true)
    }

    private fun runRows(tab: CiRunsTab): List<DefaultMutableTreeNode> {
        val tree = UIUtil.findComponentOfType(tab, Tree::class.java)!!
        return (0 until tree.model.getChildCount(tree.model.root)).map { tree.model.getChild(tree.model.root, it) as DefaultMutableTreeNode }
    }

    private fun press(tab: CiRunsTab, name: String) {
        val toolbar = UIUtil.findComponentOfType(tab.toolbar as JComponent, ActionToolbarImpl::class.java)!!
        val action = (toolbar.actionGroup as DefaultActionGroup).childActionsOrStubs.single { it.templateText == name }
        action.actionPerformed(TestActionEvent.createTestEvent(action))
    }

    fun `test CI Runs says why there are no runs, signed out, a runner error or a runner without saffron runs`() {
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        try {
            standIn(base, """echo '{"provider":"github","problem":{"kind":"signed-out","message":"Run gh auth login"}}'; exit 1""")
            val tab = CiRunsTab(project, testRootDisposable)
            fun row() = runRows(tab).singleOrNull()?.userObject as? RunNode
            PlatformTestUtil.waitWithEventsDispatching("no problem row", { row()?.label == "Not signed in" }, 60)
            assertEquals("Run gh auth login", row()!!.detail)

            // Any other failure is the runner's {error}: its reason, never "No CI runs".
            standIn(base, """echo '{"error":"gh is not installed"}'; exit 2""")
            press(tab, "Refresh")
            PlatformTestUtil.waitWithEventsDispatching("no error row", { row()?.detail == "gh is not installed" }, 60)
            assertEquals("CI runs unavailable", row()!!.label)

            standIn(base, "echo \"error: unknown command 'runs'\" >&2; exit 1")
            press(tab, "Refresh")
            val update = " Update it: npm i -D saffron-ai@latest."
            PlatformTestUtil.waitWithEventsDispatching("no update hint", { row()?.detail == "CI runs need saffron-ai 0.9.3 or later.$update" }, 60)
            assertEquals("CI runs unavailable", row()!!.label)

            // With the status's version, the hint names it.
            standInCases(base, "  runs) echo \"error: unknown command 'runs'\" >&2; exit 1 ;;\n  status) echo '{\"version\":\"0.9.2\",\"packageInstalled\":true}' ;;")
            val service = SaffronStatusService.getInstance(project)
            service.refresh()
            PlatformTestUtil.waitWithEventsDispatching("the status never reloaded", { service.latest.status?.version == "0.9.2" }, 60)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            press(tab, "Refresh")
            PlatformTestUtil.waitWithEventsDispatching("no version in the hint", { row()?.detail == "CI runs need saffron-ai 0.9.3 or later; this project has 0.9.2.$update" }, 60)
        } finally {
            base.resolve("node_modules").toFile().deleteRecursively()
        }
    }

    fun `test CI Runs lists the runs once the project is trusted`() {
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        try {
            standIn(base, """echo '{"provider":"github","runs":[{"id":"7","name":"e2e","number":"7","status":"completed","conclusion":"success"}]}'""")
            var trust = false
            val tab = CiRunsTab(project, testRootDisposable) { trust }
            val tree = UIUtil.findComponentOfType(tab, Tree::class.java)!!
            assertEquals(SaffronStatusService.UNTRUSTED, tree.emptyText.text)
            press(tab, "Import Run")
            assertEquals(SaffronStatusService.UNTRUSTED, UIUtil.findComponentsOfType(tab, JBLabel::class.java).single().text)
            // Trusting the project reloads the status, and with it the runs.
            trust = true
            SaffronStatusService.getInstance(project).refresh()
            PlatformTestUtil.waitWithEventsDispatching("the runs never loaded", { runRows(tab).map { it.toString() } == listOf("e2e #7") }, 60)
            // The untrusted note went with the trust.
            assertEquals("", UIUtil.findComponentsOfType(tab, JBLabel::class.java).single().text)
        } finally {
            base.resolve("node_modules").toFile().deleteRecursively()
        }
    }

    fun `test CI Runs shows only the chosen project's runs and imports`() {
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val a = base.resolve("a")
        val b = base.resolve("b")
        val service = SaffronStatusService.getInstance(project)
        try {
            for (dir in listOf(a, b)) {
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("saffron.config.json"), "{}")
            }
            fun runs(name: String) = """echo '{"provider":"github","runs":[{"id":"1","name":"$name","number":"1","status":"completed","conclusion":"failure"}]}'"""
            standIn(a, runs("a") + """;; import) echo '{"proposals":[]}'""")
            // b answers slowly: a later choice of a must not be overwritten by it.
            standIn(b, "sleep 3; " + runs("b"))
            service.choose(a)
            val tab = CiRunsTab(project, testRootDisposable)
            val tree = UIUtil.findComponentOfType(tab, Tree::class.java)!!
            fun names() = runRows(tab).map { it.toString() }
            PlatformTestUtil.waitWithEventsDispatching("a never listed", { names() == listOf("a #1") }, 60)
            tree.selectionPath = TreePath(runRows(tab)[0].path)
            press(tab, "Import Run")
            PlatformTestUtil.waitWithEventsDispatching("a never imported", { runRows(tab).firstOrNull()?.childCount == 1 }, 60)

            // a's rows go the moment b is chosen, not when b's status arrives: a selected a row
            // cannot be imported into b meanwhile.
            tree.selectionPath = TreePath(runRows(tab)[0].path)
            service.choose(b)
            assertEquals(emptyList<String>(), names())
            press(tab, "Import Run")
            service.choose(a)
            PlatformTestUtil.waitWithEventsDispatching("a never listed again", { names() == listOf("a #1") }, 60)
            // The import was a's earlier visit: gone with the switch.
            assertEquals(0, runRows(tab)[0].childCount)
            // b's late answer is dropped.
            val until = System.currentTimeMillis() + 4_000
            PlatformTestUtil.waitWithEventsDispatching("", { System.currentTimeMillis() > until }, 10)
            assertEquals(listOf("a #1"), names())
        } finally {
            PropertiesComponent.getInstance(project).unsetValue("ai.saffron.jetbrains.root")
            a.toFile().deleteRecursively()
            b.toFile().deleteRecursively()
            service.discoverRoots()
        }
    }

    fun `test CI Runs lists again when another branch is checked out`() {
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val c = base.resolve("c")
        val service = SaffronStatusService.getInstance(project)
        try {
            Files.createDirectories(c)
            Files.writeString(c.resolve("saffron.config.json"), "{}")
            ProcessBuilder("git", "init", "-q", "-b", "main").directory(c.toFile()).start().waitFor()
            val log = c.resolve("runs.log")
            standIn(c, """echo x >> '$log'; echo '{"provider":"github","runs":[]}'""")
            service.choose(c)
            CiRunsTab(project, testRootDisposable)
            PlatformTestUtil.waitWithEventsDispatching("never listed", { Files.exists(log) && Files.readAllLines(log).size == 1 }, 60)
            // The tab finds and watches HEAD off the EDT.
            val head = c.resolve(".git/HEAD")
            val until = System.currentTimeMillis() + 2_000
            PlatformTestUtil.waitWithEventsDispatching("", { System.currentTimeMillis() > until }, 10)
            Files.writeString(head, "ref: refs/heads/feature/login\n")
            VfsUtil.markDirtyAndRefresh(false, false, false, head.toFile())
            PlatformTestUtil.waitWithEventsDispatching("the new branch's runs were never listed", { Files.readAllLines(log).size == 2 }, 60)
        } finally {
            PropertiesComponent.getInstance(project).unsetValue("ai.saffron.jetbrains.root")
            c.toFile().deleteRecursively()
            service.discoverRoots()
        }
    }

    /** A stand-in `saffron` in [dir] from a whole shell `case` body. */
    private fun standInCases(dir: Path, cases: String) {
        val bin = dir.resolve("node_modules/.bin/saffron")
        Files.createDirectories(bin.parent)
        Files.writeString(bin, "#!/bin/sh\ncase \"\$1\" in\n$cases\n  *) echo '{\"packageInstalled\":true}' ;;\nesac\n")
        bin.toFile().setExecutable(true)
    }

    fun `test CI Runs imports a run once even across a project switch`() {
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val a = base.resolve("a")
        val b = base.resolve("b")
        val service = SaffronStatusService.getInstance(project)
        // The import runs as a background task, here too.
        System.setProperty("intellij.progress.task.ignoreHeadless", "true")
        try {
            for (dir in listOf(a, b)) {
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("saffron.config.json"), "{}")
            }
            val log = a.resolve("imports.log")
            val runs = """echo '{"provider":"github","runs":[{"id":"1","name":"a","number":"1","status":"completed","conclusion":"failure"}]}'"""
            standInCases(a, "  runs) $runs ;;\n  import) echo x >> '$log'; sleep 3; echo '{\"proposals\":[]}' ;;")
            standIn(b, """echo '{"provider":"github","runs":[]}'""")
            service.choose(a)
            val tab = CiRunsTab(project, testRootDisposable)
            val tree = UIUtil.findComponentOfType(tab, Tree::class.java)!!
            fun names() = runRows(tab).map { it.toString() }
            PlatformTestUtil.waitWithEventsDispatching("a never listed", { names() == listOf("a #1") }, 60)
            tree.selectionPath = TreePath(runRows(tab)[0].path)
            press(tab, "Import Run")
            PlatformTestUtil.waitWithEventsDispatching("the import never started", { Files.exists(log) }, 30)

            service.choose(b)
            PlatformTestUtil.waitWithEventsDispatching("b never shown", { names().isEmpty() }, 60)
            service.choose(a)
            PlatformTestUtil.waitWithEventsDispatching("a never listed again", { names() == listOf("a #1") }, 60)
            // Still in flight: a second Import Run starts nothing.
            tree.selectionPath = TreePath(runRows(tab)[0].path)
            press(tab, "Import Run")
            PlatformTestUtil.waitWithEventsDispatching("the import never finished", { runRows(tab).firstOrNull()?.childCount == 1 }, 60)
            assertEquals(1, Files.readAllLines(log).size)
        } finally {
            System.clearProperty("intellij.progress.task.ignoreHeadless")
            PropertiesComponent.getInstance(project).unsetValue("ai.saffron.jetbrains.root")
            a.toFile().deleteRecursively()
            b.toFile().deleteRecursively()
            service.discoverRoots()
        }
    }

    fun `test CI Runs Refresh twice runs at most one more list, and the later answer wins`() {
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val log = base.resolve("runs.log")
        try {
            // Each list names itself by how many came before it.
            standIn(base, """echo x >> '$log'; n=${'$'}(wc -l < '$log' | tr -d ' '); sleep 2; echo '{"provider":"github","runs":[{"id":"1","name":"r'${'$'}n'","number":"1","status":"completed"}]}'""")
            val tab = CiRunsTab(project, testRootDisposable)
            PlatformTestUtil.waitWithEventsDispatching("the first list never started", { Files.exists(log) }, 30)
            press(tab, "Refresh")
            press(tab, "Refresh")
            PlatformTestUtil.waitWithEventsDispatching("the follow-up never listed", { runRows(tab).map { it.toString() } == listOf("r2 #1") }, 60)
            val until = System.currentTimeMillis() + 3_000
            PlatformTestUtil.waitWithEventsDispatching("", { System.currentTimeMillis() > until }, 10)
            assertEquals(2, Files.readAllLines(log).size)
            assertEquals(listOf("r2 #1"), runRows(tab).map { it.toString() })
        } finally {
            base.resolve("node_modules").toFile().deleteRecursively()
            Files.deleteIfExists(log)
        }
    }

    fun `test CI Runs stops its CLI process when the project closes`() {
        if (SystemInfo.isWindows) return
        val base = Path.of(project.basePath!!)
        val pid = base.resolve("runs.pid")
        val closed = Disposer.newDisposable()
        Disposer.register(testRootDisposable, closed)
        try {
            standIn(base, "echo ${'$'}${'$'} > '$pid'; exec sleep 60")
            CiRunsTab(project, closed)
            PlatformTestUtil.waitWithEventsDispatching("saffron runs never started", { Files.exists(pid) && Files.readString(pid).isNotBlank() }, 30)
            val process = ProcessHandle.of(Files.readString(pid).trim().toLong()).get()
            Disposer.dispose(closed)
            PlatformTestUtil.waitWithEventsDispatching("saffron runs outlived the project", { !process.isAlive }, 10)
        } finally {
            base.resolve("node_modules").toFile().deleteRecursively()
            Files.deleteIfExists(pid)
        }
    }

    fun `test CI text in a tooltip is escaped, and runner errors and ages read plainly`() {
        assertEquals("<html>&lt;html&gt;&lt;img src=x&gt;<br>a b</html>", tooltipHtml("<html><img src=x>", null, "a\u0007b"))
        assertNull(tooltipHtml(null))
        // Last Run: an imported report's feature is CI text too.
        val tip = attentionTooltip(StatusAttention(feature = "<html><img src=x>", scenario = "Buy", error = "boom"), false)!!
        assertEquals("<html>&lt;html&gt;&lt;img src=x&gt; › Buy<br>boom<br>No screenshot for this one; double-click opens the scenario.</html>", tip)

        // npm and Node warnings are not the error; a line that names one is.
        assertEquals(
            "npm error could not determine executable to run",
            runnerError("npm warn exec The following package was not found and will be installed: saffron-ai\nnpm error could not determine executable to run\nnpm error A complete log of this run can be found in: /tmp/x.log"),
        )
        assertEquals("error: unknown option '--json'", runnerError("(node:4242) Warning: something old\nWarning: another\nerror: unknown option '--json'"))

        val crash = "/app/node_modules/saffron-ai/dist/cli.js:12\n    throw e;\n    ^\n\nError: Cannot find module 'x'\n    at Module._load (node:internal)\n\nNode.js v22.3.0"
        assertEquals("Error: Cannot find module 'x'", runnerError(crash))
        assertEquals("saffron: not a git repository", runnerError("saffron: not a git repository\nNode.js v22.3.0"))
        assertEquals("something broke", runnerError("    at x (y.js:1)\nsomething broke\nNode.js v22.3.0"))

        val now = Instant.parse("2026-10-01T12:00:00Z")
        assertEquals("5 min ago", ago("2026-10-01T11:55:00Z", now))
        assertEquals("3 h ago", ago("2026-10-01T09:00:00Z", now))
        assertEquals("3 days ago", ago("2026-09-28T12:00:00Z", now))
        assertNull(ago("2026-10-01T12:05:00Z", now))
        assertNull(ago("yesterday", now))
        assertNull(ago(null, now))
    }

    fun `test Open Replay names its run only to a runner that knows the option`() {
        assertTrue(ai.saffron.jetbrains.ui.runnerAtLeast("0.9.1", 0, 9, 1))
        assertTrue(ai.saffron.jetbrains.ui.runnerAtLeast("0.10.0-beta.1", 0, 9, 1))
        assertTrue(ai.saffron.jetbrains.ui.runnerAtLeast("1.0.0", 0, 9, 1))
        assertFalse(ai.saffron.jetbrains.ui.runnerAtLeast("0.9.0", 0, 9, 1))
        assertFalse(ai.saffron.jetbrains.ui.runnerAtLeast("", 0, 9, 1))
    }

    fun `test the Dashboard defines its opener before the page's own script`() {
        val page = "<html><head><style></style></head><body><script>use()</script></body></html>"
        val html = ai.saffron.jetbrains.ui.withOpener(page, "open(f, s)")
        assertTrue(html.indexOf("window.saffronOpen") in 0 until html.indexOf("</head>"))
        assertEquals(page, ai.saffron.jetbrains.ui.withOpener(page, ""))
    }
}
