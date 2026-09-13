package com.endiq.turtlelauncher.utils

import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.feature.log.Logging
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException

class LauncherProfiles {
    companion object {
        /**
         * Write a default launcher_profiles.json; without it Forge/NeoForge installs break.
         */
        @JvmStatic
        fun generateLauncherProfiles() {
            runCatching {
                File(ProfilePathHome.getGameHome(), "launcher_profiles.json").apply {
                    if (!exists()) {
                        if (parentFile?.exists() == false) parentFile?.mkdirs()
                        if (!createNewFile()) throw IOException("Failed to create launcher_profiles.json file!")
                        // Start writing the content.
                        val profilesJsonString = """{"profiles":{"default":{"lastVersionId":"latest-release"}},"selectedProfile":"default"}""".trimIndent()
                        FileUtils.write(this, profilesJsonString)
                        Logging.i(
                            "Write launcher_profiles.json",
                            "The content has already been written! \r\nFile Location: $absolutePath\r\nContents: $profilesJsonString"
                        )
                    }
                }
            }.getOrElse { e ->
                Logging.e("Write launcher_profiles.json", "Unable to generate launcher_profiles.json file!", e)
            }
        }
    }
}