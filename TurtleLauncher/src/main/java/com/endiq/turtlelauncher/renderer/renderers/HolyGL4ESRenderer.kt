package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

/**
 * Holy GL4ES — the long-standing GL4ES translation layer build FCL ships as its default/
 * fallback renderer. Recommended for Minecraft versions below 1.21.4; newer versions moved
 * enough of their rendering onto features GL4ES's OpenGL ES 2 translation doesn't cover
 * well (see [com.endiq.turtlelauncher.renderer.RendererCatalog]).
 *
 * Library names and the unique identifier are taken directly from FCL-Team/FoldCraftLauncher's
 * own RendererManager.kt / Renderer.kt (ID_GL4ES), not guessed.
 */
class HolyGL4ESRenderer : RendererInterface {
    companion object {
        const val ID = "GL4ES"
    }

    override fun getRendererId(): String = ID

    // See RendererInterface.getNativeRendererId's doc comment - "GL4ES" itself matches
    // none of the strings pojavInitOpenGL's native dispatch recognizes. Like Krypton
    // Wrapper, Holy GL4ES sits on top of a plain GLESv2/EGL context at the native layer,
    // which is what the "opengles"-prefixed branch sets up.
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "f7e985d8-6d4c-f63c-d9f1-06074dab823a"

    override fun getRendererName(): String = "Holy GL4ES"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libgl4es_114.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
