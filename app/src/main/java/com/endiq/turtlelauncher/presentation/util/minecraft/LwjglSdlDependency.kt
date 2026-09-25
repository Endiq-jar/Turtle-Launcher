package com.endiq.turtlelauncher.presentation.util.minecraft

import com.endiq.turtlelauncher.data.mojang.DownloadItem
import com.endiq.turtlelauncher.data.mojang.Library
import com.endiq.turtlelauncher.data.mojang.LibraryDownloads
import com.endiq.turtlelauncher.presentation.util.usesSdl

/**
 * Minecraft 26.3's metadata can omit the GLFW callback classes even though
 * the game-side bootstrap still resolves them before switching to SDL3.
 * Keep this exact artifact separate from the legacy Pojav GLFW payload.
 */
internal object LwjglSdlDependency {
    const val COORDINATE = "org.lwjgl:lwjgl-glfw:3.4.3+4"
    const val PATH = "org/lwjgl/lwjgl-glfw/3.4.3+4/lwjgl-glfw-3.4.3+4.jar"
    const val URL =
        "https://repo1.maven.org/maven2/org/lwjgl/lwjgl-glfw/3.4.3/" +
            "lwjgl-glfw-3.4.3.jar"

    fun isRequired(versionId: String): Boolean = usesSdl(versionId)

    fun asLibrary(): Library = Library(
        name = COORDINATE,
        downloads = LibraryDownloads(
            artifact = DownloadItem(url = URL, size = 0L, sha1 = ""),
            classifiers = null
        )
    )
}
