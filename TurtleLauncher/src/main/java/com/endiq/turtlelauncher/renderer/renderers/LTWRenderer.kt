package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface


class LTWRenderer : RendererInterface {
    companion object {
        const val ID = "LTW"
    }

    override fun getRendererId(): String = ID

    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "e7dcb6d0-bf40-44f0-9703-791c7b24c69e"

    override fun getRendererName(): String = "LTW (All Version Fast)"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libltw.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
