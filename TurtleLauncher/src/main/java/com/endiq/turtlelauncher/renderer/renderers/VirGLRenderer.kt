package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

/**
 * VirGL, via Mesa's OSMesa software rasterizer build. Library names and the unique
 * identifier are taken directly from FCL-Team/FoldCraftLauncher's own RendererManager.kt
 * (ID_VIRGL), not guessed.
 *
 * libOSMesa_81.so is sourced from FCL-Team/FoldCraftLauncher's own jniLibs
 * (FCLauncher/src/main/jniLibs) and bundled here for arm64-v8a, armeabi-v7a, and x86_64 -
 * x86 has no libOSMesa_81.so upstream, so VirGL isn't offered on that ABI (see
 * Renderers.hasRequiredLibrary, which filters renderers missing their library out of the
 * picker rather than letting them be selected and crash).
 */
class VirGLRenderer : RendererInterface {
    companion object {
        const val ID = "VIRGL"
    }

    override fun getRendererId(): String = ID

    // See RendererInterface.getNativeRendererId's doc comment - the native dispatch
    // recognizes "gallium_virgl" exactly (confirmed by disassembling pojavInitOpenGL);
    // "VIRGL" alone matches nothing and falls through to the crashing default case.
    override fun getNativeRendererId(): String = "gallium_virgl"

    override fun getUniqueIdentifier(): String = "417a7a93-d9b4-98b9-ec6e-1ea400259c1f"

    override fun getRendererName(): String = "VirGLRenderer"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libOSMesa_81.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
