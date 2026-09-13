package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

/**
 * Zink - a Vulkan-backed OpenGL implementation (Mesa's zink gallium driver, via
 * libglxshim.so/libEGL_mesa.so). Library names and the unique identifier are taken directly
 * from FCL-Team/FoldCraftLauncher's own RendererManager.kt (ID_ZINK), not guessed.
 *
 * Requires the device to actually support Vulkan - gate renderer availability on that at
 * the call site (Renderers.getCompatibleRenderers already does this by id).
 */
class ZinkRenderer : RendererInterface {
    companion object {
        const val ID = "ZINK"
    }

    override fun getRendererId(): String = ID

    // See RendererInterface.getNativeRendererId's doc comment - the native dispatch
    // recognizes "vulkan_zink" exactly (confirmed by disassembling pojavInitOpenGL);
    // "ZINK" alone matches nothing and falls through to the crashing default case.
    override fun getNativeRendererId(): String = "vulkan_zink"

    override fun getUniqueIdentifier(): String = "18d93f17-ff53-a319-fa61-58709a77bf87"

    override fun getRendererName(): String = "Zink"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libglxshim.so"

    override fun getRendererEGL(): String = "libEGL_mesa.so"
}
