package ai.saffron.jetbrains.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds `saffron` command lines the same way for every entry point (run
 * configurations, the tool window, the language server): the project-local
 * binary when `saffron-ai` is installed, `npx -p saffron-ai saffron` otherwise.
 */
object SaffronCommand {

    val COMMANDS: List<String> = listOf("run", "report", "accept", "reject", "prune")

    fun localBinary(basePath: String?): Path? {
        val p = basePath?.let { Path.of(it, "node_modules", ".bin", if (SystemInfo.isWindows) "saffron.cmd" else "saffron") }
        return if (p != null && Files.isExecutable(p)) p else null
    }

    /** `saffron` with no sub-command yet; working directory = the project. */
    fun base(basePath: String?): GeneralCommandLine {
        val local = localBinary(basePath)
        val command = if (local != null) {
            GeneralCommandLine(local.toString())
        } else {
            // "saffron" alone would resolve an unrelated npm package; the binary
            // lives in saffron-ai, so pin the package explicitly.
            GeneralCommandLine(if (SystemInfo.isWindows) "npx.cmd" else "npx", "-y", "-p", "saffron-ai", "saffron")
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
            // Listing is the safe default; the tool window adds --yes through
            // extra arguments after asking.
            "prune" -> args += "prune"
            "accept", "reject" -> {
                // Files or folders holds proposal files here; blank means every proposal.
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
        return args
    }

    fun forConfiguration(c: SaffronRunConfiguration): GeneralCommandLine =
        base(c.project.basePath).withParameters(arguments(c))

    /** One-line description, used as the suggested configuration name. */
    fun describe(c: SaffronRunConfiguration): String = "saffron " + arguments(c).joinToString(" ")

    /**
     * Paths are typed as one field, so they are parsed the way a command line
     * is: whitespace separates, quotes keep `features/order checkout.saffron`
     * one path. A trailing comma on an entry is dropped, because "a, b" was
     * the documented form before; a comma inside a name is part of the name.
     */
    fun splitPaths(paths: String): List<String> =
        ParametersListUtil.parse(paths.trim())
            .map { it.trim().trimEnd(',') }
            .filter { it.isNotEmpty() }

    /** The inverse: what the plugin writes when IT fills the field. */
    fun joinPaths(paths: List<String>): String = ParametersListUtil.join(paths)
}
