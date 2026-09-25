package com.endiq.turtlelauncher.renderer

/**
 * Launcher renderer implementation.
 */
interface RendererInterface {
    /**
     * Get the renderer id.
     */
    fun getRendererId(): String

    /**
     * Get the unique renderer id.
     */
    fun getUniqueIdentifier(): String

    /**
     * Get the renderer name.
     */
    fun getRendererName(): String

    /**
     * Get the renderer environment variables.
     */
    fun getRendererEnv(): Lazy<Map<String, String>>

    /**
     * Get the libraries that need dlopen.
     */
    fun getDlopenLibrary(): Lazy<List<String>>

    /**
     * Get the renderer libraries.
     */
    fun getRendererLibrary(): String

    /**
     * The renderer identifier string to send to pojavexec.so's native environment
     * (POJAV_RENDERER) - NOT necessarily the same as [getRendererId]. That native binary
     * (prebuilt, no source in this repo - see jni/Android.mk, whose externalNativeBuild
     * is commented out in build.gradle.kts because the .c files it lists aren't present)
     * accepts the legacy renderer names plus the `opengles*` family (LTW uses
     * `opengles3_ltw`). The native bridge treats an unknown value outside those
     * families as an invalid dispatch configuration. Default here is [getRendererId]
     * since plugin-provided renderers (RendererPluginManager) already declare the
     * correct native string directly as their id; built-in renderers override this
     * when their UI id differs from the native mapping.
     */
    fun getNativeRendererId(): String = getRendererId()

    /**
     * Get the EGL name.
     */
    fun getRendererEGL(): String? = null
}
