package com.hermeswebui.android

import com.google.common.truth.Truth.assertWithMessage
import com.hermeswebui.android.launcher.LauncherIcon
import java.io.File
import org.junit.Test

/**
 * `LauncherIconManager` reads a never-chosen alias (`COMPONENT_ENABLED_STATE_DEFAULT`) as
 * [LauncherIcon.DEFAULT], while Android shows whichever alias the manifest enables. Both must name
 * the same alias, or a fresh install shows one icon while Settings reports the other.
 */
class LauncherDefaultContractTest {
    private val manifest: String = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory }
        ?.let { File(it, "AndroidManifest.xml").readText() }
        ?: error("Run from the repository root or the app module")

    private fun aliasTag(icon: LauncherIcon): String {
        val shortName = "." + icon.aliasName.substringAfterLast('.')
        return Regex("""<activity-alias[^>]*android:name="\Q$shortName\E"[^>]*>""")
            .find(manifest)?.value
            ?: error("No activity-alias $shortName in the manifest")
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("""android:$name="([^"]+)"""").find(tag)?.groupValues?.get(1)

    @Test
    fun `only the default launcher alias is enabled in the manifest`() {
        LauncherIcon.entries.forEach { icon ->
            assertWithMessage("${icon.name} android:enabled")
                .that(attribute(aliasTag(icon), "enabled"))
                .isEqualTo((icon == LauncherIcon.DEFAULT).toString())
        }
    }

    @Test
    fun `the application icon matches the default launcher alias`() {
        val application = Regex("""<application[^>]*>""").find(manifest)?.value
            ?: error("No application tag in the manifest")
        assertWithMessage("application android:icon")
            .that(attribute(application, "icon"))
            .isEqualTo(attribute(aliasTag(LauncherIcon.DEFAULT), "icon"))
    }
}
