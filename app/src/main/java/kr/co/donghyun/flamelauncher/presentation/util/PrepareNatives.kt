package kr.co.donghyun.flamelauncher.presentation.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

class PrepareNatives {
    companion object {
        fun prepareNatives(applicationContext : Context, applicationInfo : ApplicationInfo) {
            val nativesDir = File(applicationContext.filesDir, "natives")
            if (nativesDir.exists()) nativesDir.deleteRecursively()
            val mkdirResult = nativesDir.mkdirs()
            Log.d("FLAME_LAUNCHER", "natives mkdirs: $mkdirResult, exists=${nativesDir.exists()}")


            val apkLibDir = File(applicationInfo.nativeLibraryDir)
            Log.d("FLAME_LAUNCHER", "apkLibDir: ${apkLibDir.absolutePath}, exists=${apkLibDir.exists()}")
            val files = apkLibDir.listFiles()
            Log.d("FLAME_LAUNCHER", "apkLibDir files: ${files?.size}")
            files?.forEach { soFile ->
                soFile.copyTo(File(nativesDir, soFile.name), overwrite = true)
                File(nativesDir, soFile.name).setExecutable(true, false)
            }
            val apkPath = applicationInfo.sourceDir
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                    .forEach { entry ->
                        val fileName = entry.name.substringAfterLast("/")
                        val dest = File(nativesDir, fileName)
                        if (!dest.exists()) {
                            zip.getInputStream(entry).use { input ->
                                dest.outputStream().use { input.copyTo(it) }
                            }
                            dest.setExecutable(true, false)
                        }
                    }
            }
        }

        fun copyLwjglJar(applicationContext : Context) {
            val dest = File(applicationContext.filesDir, "lwjgl3/lwjgl-glfw-classes.jar")
            dest.parentFile?.mkdirs()
            applicationContext.assets.open("lwjgl-glfw-classes.jar").use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }

        fun prePopulateLwjgl(versionId: String, applicationContext : Context) {
            val nativesDir = File(applicationContext.filesDir, "natives")
            listOf("3.2.1", "3.2.2", "3.2.1-build-12", "3.2.2-build-12", "3.3.3", "3.3.3-snapshot").forEach { version ->
                val lwjglDir = File(applicationContext.getExternalFilesDir(null), "mc_$versionId/.lwjgl/$version")
                if (lwjglDir.exists()) lwjglDir.deleteRecursively()
                lwjglDir.mkdirs()
                nativesDir.listFiles()?.forEach { soFile ->
                    soFile.copyTo(File(lwjglDir, soFile.name), overwrite = true)
                    File(lwjglDir, soFile.name).setExecutable(true, false)
                }
            }
        }
    }
}