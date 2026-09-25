package kr.co.donghyun.turtlelauncher.data.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererCompatibilityTest {
    @Test
    fun ltwUsesItsEglEntryPointAndNativeRendererId() {
        val env = Renderer.LTW.buildEnv("/tmp/cache", "/tmp/native")
        assertEquals("opengles3_ltw", env["POJAV_RENDERER"])
        assertEquals("libltw.so", env["LIBGL_NAME"])
        assertEquals("libltw.so", env["DLOPEN"])
        assertEquals("libltw.so", env["POJAVEXEC_EGL"])
        assertEquals("libEGL.so", env["LIBGL_EGL"])
    }

    @Test
    fun ltwIsAvailableInGlobalRendererChoices() {
        assertTrue(Renderer.selectableRenderers().contains(Renderer.LTW))
    }

    @Test
    fun mobileGluesKeepsItsEglProviderAndDependencyOrder() {
        val env = Renderer.MOBILEGLUES.buildEnv("/tmp/cache", "/tmp/native")
        assertEquals("opengles3", env["POJAV_RENDERER"])
        assertEquals("libmobileglues.so", env["POJAVEXEC_EGL"])
        assertEquals(
            "libmobileglues_info_getter.so,libmobileglues.so",
            env["DLOPEN"]
        )
    }
}
