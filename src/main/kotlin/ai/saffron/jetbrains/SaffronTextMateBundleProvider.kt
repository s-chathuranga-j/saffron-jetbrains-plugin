package ai.saffron.jetbrains

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Supplies the Saffron grammar (the same TextMate bundle the VS Code extension
 * and the `saffron-ai` npm package ship) to the IDE's TextMate plugin.
 *
 * The TextMate plugin wants a directory on disk, but plugin resources live
 * inside the jar, so the bundle is unpacked into the IDE system directory on
 * each load (cheap, three small files) and always reflects the installed
 * plugin version.
 */
class SaffronTextMateBundleProvider : TextMateBundleProvider {
    private val files = listOf(
        "package.json",
        "language-configuration.json",
        "syntaxes/saffron.tmLanguage.json",
    )

    override fun getBundles(): List<PluginBundle> {
        return try {
            val dir = Path.of(PathManager.getSystemPath(), "saffron-textmate")
            Files.createDirectories(dir)
            for (name in files) {
                val resource = javaClass.classLoader.getResource("textmate/$name")
                    ?: throw IOException("bundled resource missing: textmate/$name")
                val target = dir.resolve(name)
                Files.createDirectories(target.parent)
                resource.openStream().use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            }
            listOf(PluginBundle("saffron", dir))
        } catch (e: IOException) {
            thisLogger().warn("Saffron: could not unpack the TextMate bundle; .saffron files will not be highlighted", e)
            emptyList()
        }
    }
}
