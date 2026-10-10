package ai.saffron.jetbrains.ui

import ai.saffron.jetbrains.run.SaffronCommand
import com.intellij.execution.util.ExecUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import javax.swing.JPanel

/**
 * The suite's quality page that `saffron dashboard --stdout` renders, the
 * same one the VS Code extension and the browser show: tests, failing,
 * flaky, never run, healing, timing, tags. A scenario's name opens it.
 */
class DashboardTab(project: Project, parent: Disposable) : StatusTab(project, parent) {

    // An IDE whose JCEF classes this plugin cannot load (not installed, or
    // not visible to it) gets a message, not a crash.
    private val browser: JBCefBrowser? = try {
        if (JBCefApp.isSupported()) JBCefBrowser() else null
    } catch (e: LinkageError) {
        null
    } catch (e: Exception) {
        // JCEF that is there but cannot start: the message too.
        null
    }
    private val openQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val placeholder = JBLabel("This IDE build has no embedded browser. Run `saffron dashboard` to open the dashboard in a browser.", JBLabel.CENTER)
    private var latest: ProjectStatus? = null
    /** Bumped per status, shown or not: a page rendered for an earlier status (or project) is dropped. */
    private var generation = 0
    /** The project and scenarios the page on screen was rendered for: a click opens there. */
    private var shownFor: Pair<java.nio.file.Path, List<StatusFeature>>? = null
    /** A status arrived while the tab was hidden: rendering waits until it is shown. */
    private var stale = true
    /** The page as last loaded: an unchanged one is not reloaded (and scrolled to the top). */
    private var shown: String? = null

    init {
        toolbar = toolbar(action("Refresh", "Reload the status and the dashboard", AllIcons.Actions.Refresh) { refreshStatus() })
        browser?.let { b ->
            Disposer.register(parent, b)
            openQuery?.let { q ->
                Disposer.register(b, q)
                q.addHandler { payload ->
                    val (feature, scenario) = payload.split('\n', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    ApplicationManager.getApplication().invokeLater({
                        shownFor?.let { (root, features) -> openScenario(features, feature, scenario, root) }
                    }, project.disposed)
                    null
                }
            }
            // The page links nowhere: anything that tries to load is stopped.
            b.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean =
                    !(request.url.startsWith("file:///jbcefbrowser/") || request.url.startsWith("about:"))
            }, b.cefBrowser)
        }
        // Each render runs the CLI: only for a tab someone is looking at.
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && stale) render(latest)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(browser?.component ?: placeholder, BorderLayout.CENTER)
        })
        start()
    }

    override fun render(status: ProjectStatus?) {
        latest = status
        // Before anything else: a load still running for the last status must not land after this one.
        val loadFor = ++generation
        val b = browser ?: return
        if (!isShowing) {
            stale = true
            return
        }
        stale = false
        val root = projectRoot
        val features = status?.features ?: emptyList()
        when {
            status == null -> show(b, message("Loading…"), null)
            !runnerAtLeast(status.version, 0, 9, 4) ->
                show(b, message("The dashboard needs saffron-ai 0.9.4 or later; this project has ${status.version}. Run npm i -D saffron-ai@latest."), null)
            else -> {
                val base = root.toString()
                val js = openQuery?.inject("f + '\\n' + s") ?: ""
                ApplicationManager.getApplication().executeOnPooledThread {
                    var rendered = false
                    val page = try {
                        val out = ExecUtil.execAndGetOutput(SaffronCommand.base(base).withParameters("dashboard", "--stdout"), 60_000)
                        if (out.exitCode == 0 && out.stdout.isNotBlank()) withOpener(out.stdout, js).also { rendered = true }
                        else message("Could not load the dashboard: ${runnerError(out.stderr)}")
                    } catch (e: Exception) {
                        message("Could not load the dashboard: ${e.message ?: e}")
                    }
                    ApplicationManager.getApplication().invokeLater({ if (loadFor == generation) show(b, page, if (rendered) root to features else null) }, project.disposed)
                }
            }
        }
    }

    private fun show(b: JBCefBrowser, html: String, scenariosFrom: Pair<java.nio.file.Path, List<StatusFeature>>?) {
        shownFor = scenariosFrom
        if (html == shown) return
        shown = html
        b.loadHTML(html)
    }
}

/** A page in the dashboard's own colours, for the moments there is no dashboard to show. */
private fun message(text: String) =
    "<html><body style=\"margin:0;padding:24px;background:#131416;color:#7E8388;font:13px system-ui,sans-serif\">" +
        StringUtil.escapeXmlEntities(text) + "</body></html>"

/** The runner's page with `saffronOpen(feature, scenario)` defined before its own script runs. */
internal fun withOpener(html: String, openJs: String): String {
    if (openJs.isEmpty()) return html
    val script = "<script>window.saffronOpen = function (f, s) { $openJs };</script>"
    val head = html.indexOf("</head>")
    return if (head < 0) script + html else html.substring(0, head) + script + html.substring(head)
}
