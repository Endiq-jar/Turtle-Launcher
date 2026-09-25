package kr.co.donghyun.turtlelauncher.presentation

/**
 * Keeps the pre-SDL patched GLFW payload away from the Minecraft 26.3 SDL stack.
 *
 * A stale lwjgl-glfw-classes.jar can survive in an existing instance or in
 * extraJars. It contains the legacy GLFW classes and must not be allowed to
 * shadow the game's LWJGL 3.4.3 SDL classes. This policy is deliberately pure
 * so it can be tested without starting Android or a game JVM.
 */
internal object LwjglClasspathPolicy {
    fun isRedundant(fileName: String, sdlMode: Boolean): Boolean {
        val name = fileName.lowercase()
        val isLwjgl = name.startsWith("lwjgl")
        val isNativeJar = name.contains("natives") && isLwjgl

        if (sdlMode) {
            // 26.3+ uses the game's LWJGL 3.4.3 SDL Java bindings. The
            // Pojav-patched fat GLFW jar is a different generation and must
            // never be present, even when it came from an old instance.
            if (name.startsWith("lwjgl-glfw-classes")) return true
            // Native classifier jars cannot provide Android classes or natives;
            // the app's ABI-specific libraries are selected separately.
            return isNativeJar
        }

        // Legacy GLFW versions use the patched fat payload as the sole LWJGL
        // Java implementation. Remove vanilla module jars and native jars.
        if (name.startsWith("lwjgl-glfw-classes")) return false
        if (isNativeJar) return true
        return name.startsWith("lwjgl-") || name == "lwjgl.jar"
    }
}
