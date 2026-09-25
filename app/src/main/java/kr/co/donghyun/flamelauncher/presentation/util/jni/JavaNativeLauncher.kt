package kr.co.donghyun.flamelauncher.presentation.util.jni

class JavaNativeLauncher {
    companion object {
        init {
            System.loadLibrary("flamejvm")
        }
        @JvmStatic external fun preloadAwtStubs(nativeLibDir: String)
    }

    external fun bootMinecraftJVM(
        libJvmPath: String,
        jvmArgs: Array<String>,
        mcArgs: Array<String>
    ): Int

    /** Renderer 환경변수 세팅. JVM 부팅 전에 호출해야 함. */
    external fun nativeSetEnv(key: String, value: String)

    fun applyEnv(env: Map<String, String>) {
        env.forEach { (k, v) -> nativeSetEnv(k, v) }
    }
}