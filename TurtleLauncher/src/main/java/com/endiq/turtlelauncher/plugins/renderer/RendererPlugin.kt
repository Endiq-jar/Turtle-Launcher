package com.endiq.turtlelauncher.plugins.renderer

abstract class RendererPlugin(
    val id: String,
    val displayName: String,
    val uniqueIdentifier: String,
    val glName: String,
    val eglName: String,
    val path: String,
    val env: Map<String, String>,
    val dlopen: List<String>,
    /**
     * Inclusive Minecraft version range the plugin declares for itself in its manifest
     * meta-data (`minMCVer`/`maxMCVer` - e.g. the MobileGlues plugin app ships
     * minMCVer="1.17"). Null = no bound declared. Honored by the same launch-time
     * compatibility warning and picker note the built-in renderers' RendererCatalog
     * ranges get, so a plugin app is treated like any first-class renderer.
     */
    val minMinecraftVersion: String? = null,
    val maxMinecraftVersion: String? = null
)
