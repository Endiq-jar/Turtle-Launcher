package com.endiq.turtlelauncher.renderer

/**
 * 启动器渲染器实现
 */
interface RendererInterface {
    /**
     * 获取渲染器的ID
     */
    fun getRendererId(): String

    /**
     * 获取渲染器的唯一标识ID
     */
    fun getUniqueIdentifier(): String

    /**
     * 获取渲染器的名称
     */
    fun getRendererName(): String

    /**
     * 获取渲染器的环境变量
     */
    fun getRendererEnv(): Lazy<Map<String, String>>

    /**
     * 获取需要dlopen的库
     */
    fun getDlopenLibrary(): Lazy<List<String>>

    /**
     * 获取渲染器的库
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
     * 获取EGL名称
     */
    fun getRendererEGL(): String? = null
}
