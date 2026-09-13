package ai.saffron.jetbrains

import com.intellij.openapi.fileTypes.LanguageFileType
import org.jetbrains.plugins.textmate.TextMateBackedFileType
import javax.swing.Icon

/**
 * `.saffron` — Gherkin plus the `StepSet` keyword. TextMate-backed: the IDE
 * recognises and labels the file through this type, while highlighting comes
 * from the bundled grammar via [SaffronTextMateBundleProvider].
 */
class SaffronFileType private constructor() : LanguageFileType(SaffronLanguage), TextMateBackedFileType {

    companion object {
        @JvmField
        val INSTANCE: SaffronFileType = SaffronFileType()
    }

    override fun getName(): String = "Saffron"

    override fun getDescription(): String = "Saffron feature file (Gherkin + StepSet)"

    override fun getDefaultExtension(): String = "saffron"

    override fun getIcon(): Icon = SaffronIcons.FILE
}
