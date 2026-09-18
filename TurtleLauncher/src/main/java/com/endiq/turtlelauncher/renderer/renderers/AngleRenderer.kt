package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

class AngleRenderer : RendererInterface {
    companion object {
        const val ID = "ANGLE"
    }

    override fun getRendererId(): String = ID

    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "c52d3bea-a5bd-4b85-82aa-2e7472582bc8"

    override fun getRendererName(): String = "ANGLE"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libGLESv2_angle.so"

    override fun getRendererEGL(): String = "libEGL_angle.so"
}
