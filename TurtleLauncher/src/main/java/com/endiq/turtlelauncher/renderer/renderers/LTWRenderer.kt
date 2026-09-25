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

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        // Match MJLauncher’s GLES provider: LTW wraps Android’s system EGL/GLES
        // implementation while its own libltw.so remains the EGL entry point
        // passed to pojavexec.
        mapOf(
            "LIBGL_EGL" to "libEGL.so",
            // Do not set LIBGL_GLES here: this launcher's EGL bridge uses that
            // variable as its provider handle and would try to resolve EGL
            // entry points from libGLESv2.so instead of libEGL.so.
            "force_glsl_extensions_warn" to "true",
            "allow_higher_compat_version" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
            "LIBGL_NOERROR" to "1"
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libltw.so"

    // LTW exports the EGL entry points that the bridge resolves through
    // eglGetProcAddress. Using the system libEGL here bypasses LTW and leaves
    // its GL dispatch table disconnected from the current context.
    override fun getRendererEGL(): String = "libltw.so"
}
