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

    // See RendererInterface.getNativeRendererId's doc comment. LOWER CONFIDENCE than the
    // other five renderers' mappings: no VGPU launch log was available to confirm this
    // against (unlike Krypton Wrapper, VirGL, Zink, Freedreno, GL4ES). Best-supported
    // guess is "opengles" - libvgpu.so's own exported symbols (glAccum, glAlphaFunc,
    // glActiveTextureARB, etc.) are plain desktop-GL-shaped, the same architectural
    // pattern as GL4ES/Krypton Wrapper (a userspace GL implementation dlopen'd via
    // org.lwjgl.opengl.libname, sitting on top of a plain EGL/GLES native window/context -
    // not a Gallium or Vulkan one). "VGPU" itself matches none of pojavInitOpenGL's
    // recognized strings either way, so this is strictly no worse than before; if VGPU
    // still crashes after this, get a launch log and re-derive its mapping the same way
    // the other five were (see Renderers.kt's top-of-file doc comment).
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "0fb718e4-64e3-83d4-a974-8204ea1d9f9f"

    override fun getRendererName(): String = "VGPU"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libvgpu.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
