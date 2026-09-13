package com.endiq.turtlelauncher.feature.version.install

import android.content.Intent
import com.google.gson.JsonParser
import com.kdt.mcgui.ProgressLayout
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.utils.path.LibPath
import net.kdt.pojavlaunch.JavaGUILauncherActivity
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class InstallArgsUtils(private val mcVersion: String, private val loaderVersion: String) {
    fun setFabric(intent: Intent, jarFile: File, customName: String) {
        val args = "-DprofileName=\"$customName\" -javaagent:${LibPath.MIO_FABRIC_AGENT.absolutePath}" +
                " -jar ${jarFile.absolutePath} client -mcversion \"$mcVersion\" -loader \"$loaderVersion\" -dir \"${ProfilePathHome.getGameHome()}\""
        intent.putExtra("javaArgs", args)
        intent.putExtra(JavaGUILauncherActivity.SUBSCRIBE_JVM_EXIT_EVENT, true)
        intent.putExtra(JavaGUILauncherActivity.FORCE_SHOW_LOG, true)
    }

    @Deprecated("JRE 8 installs are unsupported; on higher JREs the installer does not exit on its own, so this function is not used for configured installs yet")
    fun setQuilt(intent: Intent, jarFile: File) {
        val args = "-jar ${jarFile.absolutePath} install client \"$mcVersion\" \"$loaderVersion\" --install-dir=\"${ProfilePathHome.getGameHome()}\""
        intent.putExtra("javaArgs", args)
        intent.putExtra(JavaGUILauncherActivity.SUBSCRIBE_JVM_EXIT_EVENT, true)
        intent.putExtra(JavaGUILauncherActivity.FORCE_SHOW_LOG, true)
    }

    @Throws(Throwable::class)
    fun setForge(intent: Intent, jarFile: File, customName: String) {
        forgeLikeCustomVersionName(jarFile, customName)

        val args = "-javaagent:${LibPath.FORGE_INSTALLER.absolutePath}=\"$loaderVersion\" -jar ${jarFile.absolutePath}"
        intent.putExtra("javaArgs", args)
    }

    @Throws(Throwable::class)
    fun setNeoForge(intent: Intent, jarFile: File, customName: String) {
        forgeLikeCustomVersionName(jarFile, customName)

        val args = "-jar ${jarFile.absolutePath} --installClient \"${ProfilePathHome.getGameHome()}\""
        intent.putExtra("javaArgs", args)
        intent.putExtra(JavaGUILauncherActivity.SUBSCRIBE_JVM_EXIT_EVENT, true)
        intent.putExtra(JavaGUILauncherActivity.FORCE_SHOW_LOG, true)
    }

    fun setOptiFine(intent: Intent, jarFile: File, customName: String) {
        val args = "-javaagent:${LibPath.FORGE_INSTALLER.absolutePath}=OFNPS " +
                "-javaagent:${LibPath.OPTIFINE_RENAMER.absolutePath}=\"$customName\" " +
                "-jar ${jarFile.absolutePath}"
        intent.putExtra("javaArgs", args)
    }

    /**
     * Rewrite the "version" key in the Forge/NeoForge install_profile.json to customName.
     * The Forge installer derives the version folder from the "version" value.
     * This customises where the version json is installed.
     */
    @Throws(Throwable::class)
    private fun forgeLikeCustomVersionName(jarFile: File, customName: String) {
        val tempJarFile = File(jarFile.parentFile, "${jarFile.nameWithoutExtension}_temp.jar")
        val profileJson = File(jarFile.parentFile, "install_profile.json")
        try {
            updateProgress(0)

            if (tempJarFile.exists()) tempJarFile.delete()
            extractInstallProfile(jarFile, profileJson)
            updateProgress(50)

            modifyJsonFile(profileJson, customName)
            writeTempJarFile(jarFile, tempJarFile, profileJson)
            updateProgress(100)

            if (!jarFile.delete()) throw IOException("Failed to delete original Installer file!")
            if (!tempJarFile.renameTo(jarFile)) throw IOException("Failed to rename temp Installer file to original!")
            profileJson.delete()
        } catch (e: Exception) {
            throw RuntimeException(e)
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
        }
    }

    private fun updateProgress(progress: Int) {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_RESOURCE, progress, R.string.mod_forge_custom_version)
    }

    /**
     * Extract install_profile.json.
     */
    @Throws(Throwable::class)
    private fun extractInstallProfile(jarFile: File, profileJson: File) {
        val zipFile = ZipFile(jarFile)
        val entry = zipFile.getEntry("install_profile.json")
            ?: throw IOException("File \"install_profile.json\" not found in the Installer")
        profileJson.outputStream().use { outputStream ->
            zipFile.getInputStream(entry).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    /**
     * Custom version names are applied by editing the values in install_profile.json.
     */
    @Throws(Throwable::class)
    private fun modifyJsonFile(profileJson: File, customName: String) {
        val jsonObject = JsonParser.parseString(profileJson.readText()).asJsonObject
        // The presence of the "spec" key marks a new-style installer.
        if (jsonObject.has("spec")) { // new-style installer
            if (!jsonObject.has("version")) throw IOException("Unable to find version key!")
            // Rewriting the "version" value in install_profile.json to customName applies the rename.
            jsonObject.addProperty("version", customName)
        } else { // legacy installer
            if (!jsonObject.has("install")) throw IOException("Unable to find install key!")
            val install = jsonObject.get("install").asJsonObject
            if (!install.has("target")) throw IOException("Unable to find install-target key!")
            // Changing the "target" value to customName applies the legacy rename behaviour.
            install.addProperty("target", customName)
            jsonObject.add("install", install)
        }
        profileJson.writeText(jsonObject.toString())
    }

    @Throws(Throwable::class)
    private fun writeTempJarFile(jarFile: File, tempJarFile: File, profileJson: File) {
        // Only skip .SF/.RSA entries under META-INF, so verification does not trip over the
        // modified install_profile.json.
        fun needSkip(entryName: String) = entryName.startsWith("META-INF/") && (entryName.endsWith(".SF") || entryName.endsWith(".RSA"))

        ZipFile(jarFile).use { zipFile ->
            ZipOutputStream(tempJarFile.outputStream()).use { zos ->
                zipFile.entries().asSequence().forEach { originalEntry ->
                    zos.putNextEntry(ZipEntry(originalEntry.name))
                    if (originalEntry.name == "install_profile.json") {
                        profileJson.inputStream().use { fis -> fis.copyTo(zos) }
                    } else {
                        if (!originalEntry.isDirectory && !needSkip(originalEntry.name)) {
                            // Write the raw file.
                            zipFile.getInputStream(originalEntry).use { it.copyTo(zos) }
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }
}