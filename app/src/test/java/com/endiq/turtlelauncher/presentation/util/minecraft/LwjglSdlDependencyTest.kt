package com.endiq.turtlelauncher.presentation.util.minecraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LwjglSdlDependencyTest {
    @Test
    fun dependencyIsOnlyRequiredForSdlVersions() {
        assertTrue(LwjglSdlDependency.isRequired("26.3"))
        assertTrue(LwjglSdlDependency.isRequired("26.3-snapshot-4"))
        assertFalse(LwjglSdlDependency.isRequired("26.2"))
        assertFalse(LwjglSdlDependency.isRequired("1.21.11"))
    }

    @Test
    fun dependencyKeepsExactCoordinateAndCallbackArtifact() {
        val library = LwjglSdlDependency.asLibrary()
        assertEquals(LwjglSdlDependency.COORDINATE, library.name)
        assertEquals(LwjglSdlDependency.URL, library.downloads.artifact?.url)
        assertEquals(
            "3.4.3+4",
            LwjglSdlDependency.PATH.substringAfterLast('/').removeSuffix(".jar")
                .removePrefix("lwjgl-glfw-")
        )
    }
}
