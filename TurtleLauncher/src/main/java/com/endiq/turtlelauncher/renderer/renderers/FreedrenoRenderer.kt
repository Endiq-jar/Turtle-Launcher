package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

/**
 * Freedreno - optimized primarily for Qualcomm Adreno GPUs. Library names and the unique
 * identifier are taken directly from FCL-Team/FoldCraftLauncher's own RendererManager.kt
 * (ID_FREEDRENO), not guessed.
 */
class FreedrenoRenderer : RendererInterface {
    companion object {
        const val ID = "FREEDRENO"
    }

    override fun getRendererId(): String = ID

    override fun getNativeRendererId(): String = "gallium_freedreno"

    override fun getUniqueIdentifier(): String = "8d427e6c-9d22-2d19-db0c-3b9ac2c1543f"

    override fun getRendererName(): String = "Freedreno"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libOSMesa_8.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
