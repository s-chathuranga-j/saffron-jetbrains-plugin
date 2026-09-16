package ai.saffron.jetbrains

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object SaffronIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/saffron.svg", SaffronIcons::class.java)

    @JvmField
    val TOOL_WINDOW: Icon = IconLoader.getIcon("/icons/toolwindow.svg", SaffronIcons::class.java)
}
