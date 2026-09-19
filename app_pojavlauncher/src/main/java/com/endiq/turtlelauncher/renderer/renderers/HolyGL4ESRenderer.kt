package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

class HolyGL4ESRenderer : RendererInterface {
    companion object {
        const val ID = "GL4ES"
    }

    override fun getRendererId(): String = ID

    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "f7e985d8-6d4c-f63c-d9f1-06074dab823a"

    override fun getRendererName(): String = "Holy GL4ES (Fast, till 1.21.4)"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libgl4es_114.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
