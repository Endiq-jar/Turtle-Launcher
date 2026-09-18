package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

/**
 * VGPU. Library names and the unique identifier are taken directly from
 * FCL-Team/FoldCraftLauncher's own RendererManager.kt (ID_VGPU), not guessed.
 * Recommended for Minecraft 1.16.5 and up (see [com.endiq.turtlelauncher.renderer.RendererCatalog]).
 *
 * libvgpu.so is sourced from FCL-Team/FoldCraftLauncher's own jniLibs
 * (FCLauncher/src/main/jniLibs) and bundled here for all four ABIs.
 */
class VGPURenderer : RendererInterface {
    companion object {
        const val ID = "VGPU"
    }

    override fun getRendererId(): String = ID

    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "0fb718e4-64e3-83d4-a974-8204ea1d9f9f"

    override fun getRendererName(): String = "VGPU"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libvgpu.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
