package com.endiq.turtlelauncher.launch

import androidx.collection.ArrayMap
import com.endiq.turtlelauncher.InfoDistributor
import com.endiq.turtlelauncher.feature.accounts.AccountType
import com.endiq.turtlelauncher.feature.accounts.AccountUtils
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome.Companion.getLibrariesHome
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.path.LibPath
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.AWTCanvasView
import net.endiq.launcher.JMinecraftVersionList
import net.endiq.launcher.Tools
import net.endiq.launcher.multirt.Runtime
import net.endiq.launcher.utils.JSONUtils
import net.endiq.launcher.value.MinecraftAccount
import org.jackhuang.hmcl.util.versioning.VersionNumber
import java.io.File

class LaunchArgs(
    private val account: MinecraftAccount,
    private val gameDirPath: File,
    private val minecraftVersion: Version,
    private val versionInfo: JMinecraftVersionList.Version,
    private val versionFileName: String,
    private val runtime: Runtime,
    private val launchClassPath: String
) {
    fun getAllArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        argsList.addAll(getJavaArgs())
        argsList.addAll(getMinecraftJVMArgs())
        argsList.addAll(getCdsArgs())

        argsList.removeAll { it.startsWith("-Djava.library.path=") || it.startsWith("-Djna.boot.library.path=") }
        val nativeLibraryPath = resolveNativeLibraryPath()
        argsList.add("-Djava.library.path=$nativeLibraryPath")
        argsList.add("-Djna.boot.library.path=$nativeLibraryPath")

        val lwjglAbiOverrideClasspath = Tools.getLwjglAbiOverrideClasspath(versionInfo)
        val lwjglClasspathPrefix = if (lwjglAbiOverrideClasspath.isNotEmpty()) "$lwjglAbiOverrideClasspath:" else ""

        if (Tools.resolveLwjglMode(versionInfo) == Tools.LwjglMode.NEW_SDL) {
            // Set this before -cp and before any LWJGL class can initialize.
            // Minecraft's version arguments may contain their own allocator
            // setting; this launcher-owned value must be the final effective
            // configuration for Android's system allocator path.
            argsList.add("-Dorg.lwjgl.system.allocator=system")
        }

        argsList.add("-cp")
        val launcherLwjglClasspath = Tools.getLWJGL3ClassPath(versionInfo)
        val launcherLwjglSegment = if (launcherLwjglClasspath.isNotEmpty()) "$launcherLwjglClasspath:" else ""
        // The bridge must come first: it owns the Android GLFW/Vulkan shims.
        // Its LWJGL core and callback APIs are intentionally absent. SDL
        // versions provide their exact core/SDL APIs here; the bootstrap loads
        // libpojavexec directly and never initializes the optional GLFW shim.
        argsList.add("$launcherLwjglSegment$lwjglClasspathPrefix$launchClassPath")

        val lwjglNativeOverride = Tools.getLwjglNativeLibraryOverride(versionInfo)
        if (lwjglNativeOverride != null) {
            argsList.add("-Dorg.lwjgl.libname=$lwjglNativeOverride")
        }

        // Minecraft's main class is loaded from the class path (the unnamed
        // module). Do not manufacture --add-exports from its package name:
        // `net.minecraft.client.main/net.minecraft.client.main` is not a module
        // export and Java 25 correctly warns that the module is unknown.

        if (Tools.resolveLwjglMode(versionInfo) == Tools.LwjglMode.NEW_SDL) {
            val pinnedSdl3 = File(PathManager.DIR_NATIVE_LIB, "libSDL3.so")
            if (pinnedSdl3.isFile) {
                argsList.add("-Dorg.lwjgl.sdl.libname=${pinnedSdl3.absolutePath}")
            }

            argsList.add("-Dturtlelauncher.realMainClass=${versionInfo.mainClass}")
            argsList.add("com.endiq.turtlelauncher.launch.SdlMainReadyBootstrap")
        } else {
            argsList.add(versionInfo.mainClass)
        }
        argsList.addAll(getMinecraftClientArgs())

        return argsList
    }

    private fun getJavaArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        if (AccountUtils.isOtherLoginAccount(account)) {
            val baseUrl = account.otherBaseUrl ?: ""
            if (baseUrl.contains("auth.mc-user.com") || baseUrl.contains("nide8auth")) {
                val serverId = extractNide8ServerId(baseUrl)
                argsList.add("-javaagent:${LibPath.NIDE_8_AUTH.absolutePath}=$serverId")
                argsList.add("-Dnide8auth.client=true")
            } else if (net.endiq.launcher.authenticator.BattlyAuthlibManager.isBattlyServer(baseUrl)) {
                // Battly ships its own authlib-injector build rather than the generic bundled
                // one, fetched from their file manifest — see BattlyAuthlibManager for why.
                net.endiq.launcher.authenticator.BattlyAuthlibManager.addJvmArgumentsIfAvailable(argsList)
            } else {
                argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=$baseUrl")
            }
        } else if (account.accountType == com.endiq.turtlelauncher.feature.accounts.AccountType.LOCAL.type &&
            com.endiq.turtlelauncher.setting.AllSettings.localSkinServerEnabled.getValue()
        ) {
            val port = com.endiq.turtlelauncher.feature.skin.TurtleSkinServer.ensureStarted(
                com.endiq.turtlelauncher.setting.AllSettings.localSkinServerLanVisible.getValue()
            )
            if (port > 0 && LibPath.AUTHLIB_INJECTOR.isFile) {
                val apiRoot = com.endiq.turtlelauncher.feature.skin.TurtleSkinServer.apiRootUrl()
                argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=$apiRoot")
            } else {
                Logging.e("LaunchArgs", "Local skin server unavailable (port=$port, authlib present=${LibPath.AUTHLIB_INJECTOR.isFile}) - local account will show the default skin")
            }
        }

        argsList.addAll(getCacioJavaArgs(runtime.javaVersion))

        val is7 = VersionNumber.compare(VersionNumber.asVersion(versionInfo.id ?: "0.0").canonical, "1.12") < 0
        val configFilePath = if (is7) LibPath.LOG4J_XML_1_7 else LibPath.LOG4J_XML_1_12
        argsList.add("-Dlog4j.configurationFile=${configFilePath.absolutePath}")

        return argsList
    }

    private fun resolveNativeLibraryPath(): String {
        val versionSpecificNativesDir = File(PathManager.DIR_CACHE, "natives/${minecraftVersion.getVersionName()}")
        if (!versionSpecificNativesDir.exists()) versionSpecificNativesDir.mkdirs()
        return "${versionSpecificNativesDir.absolutePath}:${PathManager.DIR_NATIVE_LIB}"
    }

    private fun getCdsArgs(): List<String> {
        return emptyList()
    }

    private fun getMinecraftJVMArgs(): Array<String> {
        val versionInfo = Tools.getVersionInfo(minecraftVersion, true)

        val varArgMap: MutableMap<String, String?> = android.util.ArrayMap()
        varArgMap["classpath_separator"] = ":"
        varArgMap["library_directory"] = getLibrariesHome()
        varArgMap["version_name"] = versionInfo.id
        val writableNativesDir = File(PathManager.DIR_CACHE, "natives/${versionInfo.id}").apply {
            if (!exists()) mkdirs()
        }
        varArgMap["natives_directory"] = writableNativesDir.absolutePath

        val minecraftArgs: MutableList<String> = java.util.ArrayList()
        versionInfo.arguments?.let {
            fun String.addIgnoreListIfHas(): String {
                if (startsWith("-DignoreList=")) return "$this,$versionFileName.jar"
                return this
            }
            it.jvm?.forEach { arg ->
                if (arg is String) {
                    minecraftArgs.add(arg.addIgnoreListIfHas())
                }
            }
        }
        return JSONUtils.insertJSONValueList(minecraftArgs.toTypedArray<String>(), varArgMap)
    }

    private fun getMinecraftClientArgs(): Array<String> {
        val verArgMap: MutableMap<String, String> = ArrayMap()
        // These Java fields are platform types: a null sneaking in (partial account
        // JSON, legacy file, version inheriting from a parent) NPEs the non-null
        // map put / replace call and kills the launch. Defaults keep it alive.
        verArgMap["auth_session"] = account.accessToken ?: "0"
        verArgMap["auth_access_token"] = account.accessToken ?: "0"
        verArgMap["auth_player_name"] = account.username ?: "Steve"
        verArgMap["auth_uuid"] = (account.profileId ?: "00000000-0000-0000-0000-000000000000").replace("-", "")
        verArgMap["auth_xuid"] = account.xuid ?: "0"
        verArgMap["assets_root"] = ProfilePathHome.getAssetsHome()
        verArgMap["assets_index_name"] = versionInfo.assets ?: "legacy"
        verArgMap["game_assets"] = ProfilePathHome.getAssetsHome()
        verArgMap["game_directory"] = gameDirPath.absolutePath
        verArgMap["user_properties"] = "{}"
        verArgMap["user_type"] = if (account.accountType == AccountType.MICROSOFT.type) "msa" else "legacy"
        verArgMap["version_name"] = versionInfo.inheritsFrom ?: versionInfo.id ?: "unknown"

        setLauncherInfo(verArgMap)

        val minecraftArgs: MutableList<String> = ArrayList()
        versionInfo.arguments?.apply {
            game.forEach { if (it is String) minecraftArgs.add(it) }
        }

        return JSONUtils.insertJSONValueList(
            splitAndFilterEmpty(
                versionInfo.minecraftArguments ?:
                Tools.fromStringArray(minecraftArgs.toTypedArray())
            ), verArgMap
        )
    }

    private fun setLauncherInfo(verArgMap: MutableMap<String, String>) {
        verArgMap["launcher_name"] = InfoDistributor.LAUNCHER_NAME
        verArgMap["launcher_version"] = ZHTools.getVersionName()
        verArgMap["version_type"] = minecraftVersion.getCustomInfo()
            .takeIf { it.isNotEmpty() && it.isNotBlank() }
            ?: versionInfo.type
    }

    private fun splitAndFilterEmpty(arg: String): Array<String> {
        val list: MutableList<String> = ArrayList()
        arg.split(" ").forEach {
            if (it.isNotEmpty()) list.add(it)
        }
        return list.toTypedArray()
    }

    companion object {

        /**
         * Returns the actual Java major version required for [mcVersionId],
         * fully overriding Mojang's JSON value since it isn't reliable across
         * the whole version range (only used as a last-resort fallback for
         * versions we don't explicitly recognise, e.g. very old alphas).
         */
        @JvmStatic
        fun resolveRequiredJava(mcVersionId: String, jsonMajorVersion: Int): Int {
            if (leadingVersionSegmentInt(mcVersionId, 0) >= 26) {
                // 26.x branch (new versioning scheme, no "1." prefix) — 26.1+ needs Java 25
                if (isMinecraftVersionAtLeast(mcVersionId, 26, 1, 0)) return 25
                // Very early 26.0.x betas that predate the 26.1 cutoff — no
                // explicit rule for these yet, defer to Mojang's JSON value.
                return if (jsonMajorVersion > 0) jsonMajorVersion else 25
            }

            // 1.x branch
            if (isMinecraftVersionAtLeast(mcVersionId, 1, 20, 5)) return 21
            if (isMinecraftVersionAtLeast(mcVersionId, 1, 18, 0)) return 17
            if (isMinecraftVersionAtLeast(mcVersionId, 1, 0, 0)) return 8

            // Unrecognised/non-numeric version id (e.g. custom modpack labels)
            // — fall back to whatever Mojang's JSON says rather than guessing.
            return if (jsonMajorVersion > 0) jsonMajorVersion else 8
        }

        /**
         * Extracts the leading run of digits from the dot-segment at [index] of
         * [mcVersionId] (e.g. segment "3-snapshot-4" → 3, "21" → 21). Returns
         * [default] if the segment is missing or has no leading digits.
         */
        private fun leadingVersionSegmentInt(mcVersionId: String, index: Int, default: Int = 0): Int {
            val segment = mcVersionId.split(".").getOrNull(index) ?: return default
            val digits = segment.takeWhile { it.isDigit() }
            return digits.toIntOrNull() ?: default
        }

        /**
         * Returns true when [mcVersionId] is at least [major].[minor].[patch].
         *
         * Handles both new-style "26.x.y" and legacy "1.x.y" MC version IDs, as
         * well as snapshot/pre-release suffixes on any segment (e.g.
         * "26.3-snapshot-4", "1.21.4-rc1") by only reading the leading digits
         * of each dot-separated segment and ignoring everything after them.
         * Missing patch segment is treated as 0.
         */
        @JvmStatic
        fun isMinecraftVersionAtLeast(
            mcVersionId: String,
            major: Int,
            minor: Int,
            patch: Int = 0
        ): Boolean {
            return try {
                val vMajor = leadingVersionSegmentInt(mcVersionId, 0)
                val vMinor = leadingVersionSegmentInt(mcVersionId, 1)
                val vPatch = leadingVersionSegmentInt(mcVersionId, 2)
                when {
                    vMajor != major -> vMajor > major
                    vMinor != minor -> vMinor > minor
                    else            -> vPatch >= patch
                }
            } catch (_: Exception) {
                false
            }
        }

        @JvmStatic
        fun getCacioJavaArgs(isJava8: Boolean): List<String> {
            return getCacioJavaArgs(if (isJava8) 8 else 17)
        }

        /**
         * Builds the Cacio arguments for the selected runtime. Java 25 removed
         * sun.java2d.SurfaceManagerFactory, so Java 25+ uses the bundled
         * software-only Cacio variant and never installs the Java 17 premain
         * agent. The regular Java 8/17/21 path keeps its existing toolkit and
         * agent setup.
         */
        @JvmStatic
        fun getCacioJavaArgs(javaVersion: Int): List<String> {
            val argsList: MutableList<String> = ArrayList()
            val isJava8 = javaVersion == 8

            argsList.add("-Djava.awt.headless=false")
            argsList.add("-Dcacio.managed.screensize=" + AWTCanvasView.AWT_CANVAS_WIDTH + "x" + AWTCanvasView.AWT_CANVAS_HEIGHT)
            argsList.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
            argsList.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
            argsList.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")
            if (isJava8) {
                argsList.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
            } else {
                argsList.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
                if (javaVersion < 25) {
                    argsList.add("-javaagent:" + LibPath.CACIO_17_AGENT.getAbsolutePath())
                }
                argsList.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED")
                // sun.security.action is not present in Java 25's java.base;
                // passing this export produces a warning and can break strict
                // launchers. Java 8/17/21 keep the compatibility export.
                if (javaVersion < 25) {
                    argsList.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED")
                }
                argsList.add("--add-opens=java.base/java.util=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.net=ALL-UNNAMED")
            }

            val cacioClassPath = StringBuilder()
            cacioClassPath.append("-Xbootclasspath/").append(if (isJava8) "p" else "a")
            val cacioFiles = when {
                isJava8 -> LibPath.CACIO_8
                javaVersion >= 25 -> LibPath.CACIO_25
                else -> LibPath.CACIO_17
            }
            cacioFiles.listFiles()?.onEach {
                if (it.name.endsWith(".jar")) cacioClassPath.append(":").append(it.absolutePath)
            }
            argsList.add(cacioClassPath.toString())

            return argsList
        }

        fun extractNide8ServerId(baseUrl: String): String {
            val cleaned = baseUrl.trimEnd('/')
            val prefixes = listOf(
                "https://auth.mc-user.com:233/",
                "http://auth.mc-user.com:233/",
                "https://nide8auth.com:233/",
                "http://nide8auth.com:233/"
            )
            for (prefix in prefixes) {
                if (cleaned.startsWith(prefix)) {
                    return cleaned.removePrefix(prefix).trimEnd('/')
                }
            }
            val lastSlash = cleaned.lastIndexOf('/')
            return if (lastSlash >= 0 && lastSlash < cleaned.length - 1)
                cleaned.substring(lastSlash + 1)
            else
                cleaned
        }
    }
}
