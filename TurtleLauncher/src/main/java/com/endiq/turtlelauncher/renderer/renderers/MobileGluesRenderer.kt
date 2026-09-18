package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.renderer.RendererInterface
import java.io.File

class MobileGluesRenderer : RendererInterface {
    companion object {
        const val ID = "MOBILEGLUES"
    }

    override fun getRendererId(): String = ID

    // MobileGlues presents itself as a normal EGL/GLESv2 implementation to the native
    // dispatch layer even though it may translate through ANGLE/Vulkan internally
    // (enableAngle is its own internal setting, dlopen'd by itself if used) - it isn't one
    // of the Mesa gallium/vulkan_zink-specific strings, so "opengles" is correct here too.
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "4b4b8e4b-083d-429c-97e1-5e8239b6dc17"

    override fun getRendererName(): String = "MobileGlues"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        val dirPath = File(PathManager.DIR_FILE, "mobileglues").apply { mkdirs() }.absolutePath
        mapOf("MG_DIR_PATH" to dirPath)
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libmobileglues.so"

    override fun getRendererEGL(): String = "libmobileglues.so"
}
