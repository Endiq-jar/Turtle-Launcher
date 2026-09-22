package com.endiq.turtlelauncher.renderer

import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.renderer.renderers.AngleRenderer
import com.endiq.turtlelauncher.renderer.renderers.FreedrenoRenderer
import com.endiq.turtlelauncher.renderer.renderers.HolyGL4ESRenderer
import com.endiq.turtlelauncher.renderer.renderers.LTWRenderer
import com.endiq.turtlelauncher.renderer.renderers.MobileGluesRenderer
import com.endiq.turtlelauncher.renderer.renderers.NWRenderer
import com.endiq.turtlelauncher.renderer.renderers.VGPURenderer
import com.endiq.turtlelauncher.renderer.renderers.VirGLRenderer
import com.endiq.turtlelauncher.renderer.renderers.ZinkRenderer
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import java.io.File


object Renderers {
    private val renderers: MutableList<RendererInterface> = mutableListOf()
    private var compatibleRenderers: Pair<RenderersList, MutableList<RendererInterface>>? = null
    private var currentRenderer: RendererInterface? = null
    private var isInitialized: Boolean = false

    fun init(reset: Boolean = false) {
        if (isInitialized && !reset) return
        isInitialized = true

        if (reset) {
            renderers.clear()
            compatibleRenderers = null
            currentRenderer = null
        }

        addRenderers(
            MobileGluesRenderer(),
            LTWRenderer(),
            HolyGL4ESRenderer(),
            NWRenderer(),
            AngleRenderer(),
            VirGLRenderer(),
            ZinkRenderer(),
            FreedrenoRenderer(),
            VGPURenderer()
        )
    }


    fun getCompatibleRenderers(context: Context): Pair<RenderersList, List<RendererInterface>> = compatibleRenderers ?: run {
        val deviceHasVulkan = Tools.checkVulkanSupport(context.packageManager)

        val compatibleRenderers1: MutableList<RendererInterface> = mutableListOf()
        renderers.forEach { renderer ->
         
            if (renderer.getRendererId() == ZinkRenderer.ID && !deviceHasVulkan) return@forEach

            // LTW creates an OpenGL ES 3 context. Showing it on an ES 2-only
            // device lets the picker select a renderer that will inevitably
            // fail inside eglCreateContext during RenderSystem initialization.
            if (renderer.getRendererId() == LTWRenderer.ID) {
                val gles3Unavailable = runCatching { Tools.getDetectedVersion() < 3 }.getOrDefault(true)
                val powerVrLtwFailure = File("/vendor/lib64/libsrv_um.so").exists() ||
                    File("/vendor/lib/libsrv_um.so").exists()
                if (gles3Unavailable) {
                    Logging.w("Renderers", "LTW requires OpenGL ES 3; excluding it on this device")
                    return@forEach
                }
                // LTW is known to leave no current context on PowerVR Rogue
                // devices. Exclude it before the picker can persist a renderer
                // that will crash during RenderSystem initialization; MobileGlues
                // remains available as the safe GLES fallback.
                if (powerVrLtwFailure) {
                    Logging.w("Renderers", "LTW is disabled on PowerVR devices because it cannot create a current GL context")
                    return@forEach
                }
            }

            if (!hasRequiredLibrary(renderer)) {
                Logging.w("Renderers", "${renderer.getRendererName()} (${renderer.getRendererId()}) references a library not found in this ABI's jniLibs - excluding it from the picker")
                return@forEach
            }
            compatibleRenderers1.add(renderer)
        }

        val rendererIdentifiers: MutableList<String> = mutableListOf()
        val rendererNames: MutableList<String> = mutableListOf()
        compatibleRenderers1.forEach { renderer ->
            rendererIdentifiers.add(renderer.getUniqueIdentifier())
            rendererNames.add(renderer.getRendererName())
        }

        val rendererPair = Pair(RenderersList(rendererIdentifiers, rendererNames), compatibleRenderers1)
        compatibleRenderers = rendererPair
        rendererPair
    }

    private fun hasRequiredLibrary(renderer: RendererInterface): Boolean {
        fun exists(libName: String): Boolean =
            if (libName.startsWith("/")) File(libName).exists()
            else File(PathManager.DIR_NATIVE_LIB, libName).exists()
        if (!exists(renderer.getRendererLibrary())) return false
        renderer.getRendererEGL()?.let { eglName ->

            if (eglName != "libEGL.so" && !exists(eglName)) return false
        }
        return true
    }


    @JvmStatic
    fun addRenderers(vararg renderers: RendererInterface) {
        renderers.forEach { renderer ->
            addRenderer(renderer)
        }
    }


    @JvmStatic
    fun addRenderer(renderer: RendererInterface): Boolean {
        return if (this.renderers.any { it.getUniqueIdentifier() == renderer.getUniqueIdentifier() }) {
            Logging.w("Renderers", "The unique identifier of this renderer (${renderer.getRendererName()} - ${renderer.getUniqueIdentifier()}) conflicts with an already loaded renderer. " +
                    "Normally, this shouldn't happen. You deliberately caused this conflict, didn't you, user?")
            false
        } else {
            this.renderers.add(renderer)
            Logging.i("Renderers", "Renderer loaded: ${renderer.getRendererName()} (${renderer.getRendererId()} - ${renderer.getUniqueIdentifier()})")
            true
        }
    }

    fun setCurrentRenderer(context: Context, uniqueIdentifier: String, retryToFirstOnFailure: Boolean = true) {
        if (!isInitialized) throw IllegalStateException("Uninitialized renderer!")
        val compatibleRenderers = getCompatibleRenderers(context).second
        currentRenderer = compatibleRenderers.find { it.getUniqueIdentifier() == uniqueIdentifier } ?: run {
            if (retryToFirstOnFailure) {
                val renderer = compatibleRenderers[0]
                Logging.w("Renderers", "Incompatible renderer $uniqueIdentifier will be replaced with ${renderer.getUniqueIdentifier()} (${renderer.getRendererName()})")
                renderer
            } else null
        }
    }

    fun removeRenderers(uniqueIdentifiers: Collection<String>) {
        if (uniqueIdentifiers.isEmpty()) return
        renderers.removeAll { it.getUniqueIdentifier() in uniqueIdentifiers }
        currentRenderer?.let { cur ->
            if (cur.getUniqueIdentifier() in uniqueIdentifiers) currentRenderer = null
        }
        compatibleRenderers = null
    }

    fun getCurrentRenderer(): RendererInterface {
        if (!isInitialized) throw IllegalStateException("Uninitialized renderer!")
        return currentRenderer ?: throw IllegalStateException("Current renderer not set")
    }

    fun isCurrentRendererValid(): Boolean = isInitialized && this.currentRenderer != null
}
