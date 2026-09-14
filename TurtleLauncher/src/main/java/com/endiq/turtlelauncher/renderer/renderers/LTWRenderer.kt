package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface


class LTWRenderer : RendererInterface {
    companion object {
        const val ID = "LTW"
    }

    override fun getRendererId(): String = ID

    // Same reasoning as HolyGL4ES/Krypton Wrapper: LTW doesn't implement eglGetDisplay/
    // eglInitialize itself (confirmed via nm -D - absent), it dlopens the real system EGL
    // internally and only interposes specific GL entry points, so it's still a plain
    // GLESv2/EGL context as far as pojavInitOpenGL's native dispatch is concerned.
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "e7dcb6d0-bf40-44f0-9703-791c7b24c69e"

    override fun getRendererName(): String = "LTW (All Version Fast)"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libltw.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
