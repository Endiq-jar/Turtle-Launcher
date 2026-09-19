package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.renderer.RendererInterface
import java.io.File


class NWRenderer : RendererInterface {
    companion object {
        const val ID = "NW"
    }

    override fun getRendererId(): String = ID

    // Same reasoning as Holy GL4ES/LTW/MobileGlues: NW sits on top of a plain GLESv2/EGL
    // context as far as pojavInitOpenGL's native dispatch is concerned - none of its own
    // strings match the "custom_gallium"/"vulkan_zink"/"gallium_*" native branches.
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "03543a99-7f9b-47ba-8ae3-1e5162db9fa6"

    override fun getRendererName(): String = "NW (Wrapper, Experimental)"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        val dirPath = File(PathManager.DIR_FILE, "nw").apply { mkdirs() }.absolutePath
        mapOf("NGG_DIR_PATH" to dirPath)
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libnw.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
