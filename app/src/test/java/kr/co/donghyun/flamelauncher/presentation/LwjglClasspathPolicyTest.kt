package kr.co.donghyun.flamelauncher.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LwjglClasspathPolicyTest {
    @Test
    fun sdlModeRejectsStalePatchedGlfwPayload() {
        assertTrue(LwjglClasspathPolicy.isRedundant("lwjgl-glfw-classes.jar", sdlMode = true))
        assertTrue(LwjglClasspathPolicy.isRedundant("lwjgl-glfw-classes-3.3.3.jar", sdlMode = true))
    }

    @Test
    fun sdlModeKeepsJavaBindingsAndRejectsNativeClassifiers() {
        assertFalse(LwjglClasspathPolicy.isRedundant("lwjgl-sdl-3.4.3.jar", sdlMode = true))
        assertFalse(LwjglClasspathPolicy.isRedundant("lwjgl-3.4.3.jar", sdlMode = true))
        assertTrue(LwjglClasspathPolicy.isRedundant("lwjgl-sdl-3.4.3-natives-linux.jar", sdlMode = true))
    }

    @Test
    fun legacyModeKeepsOnlyPatchedPayload() {
        assertFalse(LwjglClasspathPolicy.isRedundant("lwjgl-glfw-classes.jar", sdlMode = false))
        assertTrue(LwjglClasspathPolicy.isRedundant("lwjgl-opengl-3.3.3.jar", sdlMode = false))
        assertTrue(LwjglClasspathPolicy.isRedundant("lwjgl.jar", sdlMode = false))
    }
}
