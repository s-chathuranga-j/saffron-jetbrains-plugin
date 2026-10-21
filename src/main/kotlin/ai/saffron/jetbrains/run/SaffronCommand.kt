package ai.saffron.jetbrains.run

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds `saffron` command lines the same way for every entry point (run
 * configurations, the tool window, the language server): the project-local
 * binary when `saffron-ai` is installed (here or in a folder above, up to the
 * IDE project), `npx -p saffron-ai@<declared range> saffron` otherwise.
 */
object SaffronCommand {

    val COMMANDS: List<String> = listOf("run", "report", "trace", "accept", "reject", "prune", "login")

    /** Nothing is spawned for a project the user has not trusted: its node_modules and config are its own code. */
    fun trusted(project: Project): Boolean = isTrusted?.invoke(null, project) as? Boolean ?: false

    /**
     * The platform's trust check, looked up once: TrustedProjects.isProjectTrusted(Project)
     * where it exists (251 and later), else the `isTrusted()` extension that 251
     * deprecated (242 to 243). Reflective both ways so no build carries a call
     * to the deprecated one; neither found means untrusted.
     */
    private val isTrusted: java.lang.reflect.Method? = listOf(
        "com.intellij.ide.trustedProjects.TrustedProjects" to "isProjectTrusted",
        "com.intellij.ide.impl.TrustedProjects" to "isTrusted",
    ).firstNotNullOfOrNull { (owner, method) ->
        runCatching { Class.forName(owner).getMethod(method, Project::class.java) }.getOrNull()
            ?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
    }

    /**
     * The folders Node looks in for [basePath]'s packages: [basePath], then each one up to
     * [top] (the IDE project holding it), never above. Outside [top], [basePath] alone.
     */
    fun upward(basePath: String?, top: String? = topOf(basePath)): List<Path> {
        val start = basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return emptyList()
        val stop = top?.let { Path.of(it).toAbsolutePath().normalize() }?.takeIf { start.startsWith(it) } ?: return listOf(start)
        return generateSequence(start) { dir -> dir.parent?.takeIf { dir != stop } }.toList()
    }

    /** The folder of the open IDE project that owns [basePath]: the innermost of [roots] holding it. */
    internal fun topOf(
        basePath: String?,
        roots: List<String> = runCatching { com.intellij.openapi.project.ProjectManager.getInstance().openProjects }.getOrNull().orEmpty().mapNotNull { it.basePath },
    ): String? = basePath?.let { path ->
        roots.filter { com.intellij.openapi.util.io.FileUtil.isAncestor(it, path, false) }.maxByOrNull { it.length }
    }

    /** The project's own `saffron` binary: the nearest install, from [basePath] up to [top]. */
    fun localBinary(basePath: String?, top: String? = topOf(basePath)): Path? =
        upward(basePath, top).map { it.resolve("node_modules/.bin/" + if (SystemInfo.isWindows) "saffron.cmd" else "saffron") }.firstOrNull(Files::isExecutable)

    /** The nearest saffron-ai install, from [basePath] up to [top], as Node resolves it; null without one. */
    fun installedPackage(basePath: String?, top: String? = topOf(basePath)): Path? =
        upward(basePath, top).map { it.resolve("node_modules/saffron-ai") }.firstOrNull(Files::isDirectory)

    /** The version of [installedPackage]; null without one, or without a readable version. */
    fun installedVersion(basePath: String?, top: String? = topOf(basePath)): String? =
        installedPackage(basePath, top)?.let { pkg ->
            runCatching { JsonParser.parseString(Files.readString(pkg.resolve("package.json"))).asJsonObject.get("version").asString }.getOrNull()
        }

    /**
     * What npx runs without an install: the saffron-ai the nearest package.json declares
     * (devDependencies, then dependencies), so a clone not yet installed runs the version it
     * was written for. A spec npx cannot take from here (file:, git, workspace:), or none,
     * leaves it unpinned.
     */
    fun npxPackage(basePath: String?, top: String? = topOf(basePath)): String {
        for (dir in upward(basePath, top)) {
            val pkg = runCatching { JsonParser.parseString(Files.readString(dir.resolve("package.json"))).asJsonObject }.getOrNull() ?: continue
            val spec = listOf("devDependencies", "dependencies").firstNotNullOfOrNull { key ->
                runCatching { pkg.getAsJsonObject(key)?.get("saffron-ai")?.asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            } ?: continue
            return if (NPX_RANGE.matches(spec)) "saffron-ai@$spec" else "saffron-ai"
        }
        return "saffron-ai"
    }

    /**
     * The lowest version [npxPackage] lets npx run: an exact version, or a range's lower
     * bound (^1.0.0, ~0.9.5, >=0.9.0, 0.9.*); null when it runs the newest (no spec, `*`, `latest`).
     */
    fun npxVersion(basePath: String?, top: String? = topOf(basePath)): String? =
        lowerBound(npxPackage(basePath, top).removePrefix("saffron-ai").removePrefix("@"))

    internal fun lowerBound(spec: String): String? {
        if (spec.trimStart().startsWith("<")) return "0.0.0"
        val version = Regex("\\d+(\\.(\\d+|[xX*]))*").find(spec)?.value ?: return null
        return (version.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)).take(3).joinToString(".")
    }

    /** A range npm reads (^1.0.0, 0.9.*, >=0.9 <1), wildcards included. */
    private val NPX_RANGE = Regex("[\\w.^~<>=|* -]+")

    /** `saffron` with no sub-command yet; working directory = the project. */
    fun base(basePath: String?, local: Path? = localBinary(basePath)): GeneralCommandLine {
        val command = if (local != null) {
            GeneralCommandLine(local.toString())
        } else {
            // "saffron" alone would resolve an unrelated npm package; the binary
            // lives in saffron-ai, so pin the package explicitly.
            GeneralCommandLine(if (SystemInfo.isWindows) "npx.cmd" else "npx", "-y", "-p", npxPackage(basePath), "saffron")
        }
        if (basePath != null) command.withWorkDirectory(basePath)
        // The login-shell environment, so Node from nvm/Homebrew is found even
        // when the IDE was started from the Dock.
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        return command
    }

    /** The arguments after `saffron` for a configuration. */
    fun arguments(c: SaffronRunConfiguration): List<String> {
        val args = mutableListOf<String>()
        when (c.command) {
            "report" -> args += "report"
            // The execution replay: the field holds one scenario name (or
            // feature:scenario). Blank opens every traced scenario, starting
            // on a failed one.
            "trace" -> args += "trace"
            // Listing is the safe default; the tool window adds --yes through
            // extra arguments after asking.
            "prune" -> args += "prune"
            // The field holds the provider; blank checks the configured one.
            "login" -> {
                args += "login"
                c.paths.trim().takeIf { it.isNotEmpty() }?.let { args += it }
            }
            "accept", "reject" -> {
                // The field holds proposal files here; blank means every proposal.
                args += c.command
                val files = splitPaths(c.paths)
                if (files.isEmpty()) args += "--all" else args += files
            }
            else -> {
                args += "run"
                args += splitPaths(c.paths)
                if (c.tags.isNotBlank()) args += listOf("--filter", c.tags.trim())
                if (c.replayOnly) args += "--no-agent"
                if (c.headed) args += "--headed"
                if (c.rerecord) args += "--rerecord"
            }
        }
        if (c.extraArgs.isNotBlank()) args += ParametersListUtil.parse(c.extraArgs.trim())
        // The name last, after `--`: a CI scenario named "--port 1" is a name, never an option.
        if (c.command == "trace") traceName(c.paths)?.let { args += listOf("--", it) }
        return args
    }

    fun forConfiguration(c: SaffronRunConfiguration): GeneralCommandLine =
        base(c.resolvedWorkingDirectory).withParameters(arguments(c))

    /** One-line description, used as the suggested configuration name. */
    fun describe(c: SaffronRunConfiguration): String = "saffron " + arguments(c).joinToString(" ")

    /**
     * Paths are typed as one field, so they are parsed the way a command line
     * is: whitespace separates, quotes keep `features/order checkout.saffron`
     * one path. A trailing comma on an entry is dropped, because "a, b" was
     * the documented form before; a comma inside a name is part of the name,
     * and so is one ending a scenario's (`login.saffron:Pay, then ship,`).
     */
    fun splitPaths(paths: String): List<String> =
        ParametersListUtil.parse(paths.trim())
            .map { it.trim().let { p -> if (SCENARIO_TARGET.containsMatchIn(p)) p else p.trimEnd(',') } }
            .filter { it.isNotEmpty() }

    /** `file.saffron:12` or `file.feature:Name`: a scenario of a file, as `saffron run` takes it. */
    private val SCENARIO_TARGET = Regex("\\.(saffron|feature):")

    /** The inverse: what the plugin writes when IT fills the field. */
    fun joinPaths(paths: List<String>): String = ParametersListUtil.join(paths)

    /**
     * The scenario a trace field names; null when blank. A value wrapped
     * whole in double quotes, as the tooltip says for one with a space, is
     * unquoted. Any other value is the name as typed: split into words, a
     * name the runner lists as `Search for "red shoes"` would lose its
     * quotes and match nothing.
     */
    fun traceName(field: String): String? {
        val typed = field.trim()
        val parsed = ParametersListUtil.parse(typed)
        val name = if (typed.startsWith('"') && parsed.size == 1) parsed[0] else typed
        return name.ifBlank { null }
    }
}
