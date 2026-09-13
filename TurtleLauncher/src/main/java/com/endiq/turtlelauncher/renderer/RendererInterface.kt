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
     * has its own hardcoded, pre-FCL-refactor set of recognized POJAV_RENDERER strings
     * (found by disassembling pojavInitOpenGL in the per-ABI jniLibs libpojavexec.so - see
     * Renderers.kt's top-of-file doc comment for the full mapping table). A string it
     * doesn't recognize falls through its whole strcmp chain into an unguarded call
     * through an uninitialized function pointer - pc=0x0, guaranteed SIGSEGV, on every
     * device, every time, regardless of GPU. Default here is [getRendererId] since
     * plugin-provided renderers (RendererPluginManager) already declare the correct
     * native string directly as their id; only the six built-in renderer classes need
     * to override this with their real mapping.
     */
    fun getNativeRendererId(): String = getRendererId()

    /**
     * Get the EGL name.
     */
    fun getRendererEGL(): String? = null
}
