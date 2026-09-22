package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface


class LTWRenderer : RendererInterface {
    companion object {
        const val ID = "LTW"
    }

    override fun getRendererId(): String = ID

    // LTW is a GLES 3 translation layer, not the generic GL4ES path.  The
    // native bridge accepts this opengles-prefixed id and can keep LTW's EGL
    // provider selected all the way through context creation.
    override fun getNativeRendererId(): String = "opengles3_ltw"

    override fun getUniqueIdentifier(): String = "e7dcb6d0-bf40-44f0-9703-791c7b24c69e"

    override fun getRendererName(): String = "LTW (All Version Fast)"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libltw.so"

    // LTW exports the EGL entry points that the bridge resolves through
    // eglGetProcAddress. Using the system libEGL here bypasses LTW and leaves
    // its GL dispatch table disconnected from the current context.
    override fun getRendererEGL(): String = "libltw.so"
}
