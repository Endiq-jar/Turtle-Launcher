package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

class VirGLRenderer : RendererInterface {
    companion object {
        const val ID = "VIRGL"
    }

    override fun getRendererId(): String = ID

    override fun getNativeRendererId(): String = "gallium_virgl"

    override fun getUniqueIdentifier(): String = "417a7a93-d9b4-98b9-ec6e-1ea400259c1f"

    override fun getRendererName(): String = "VirGLRenderer"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libOSMesa_81.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
