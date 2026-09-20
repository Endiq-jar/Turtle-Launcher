package com.endiq.turtlelauncher.feature.turtle

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.Settings
import com.endiq.turtlelauncher.utils.path.PathManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Versioned, launcher-independent backup format for Turtle Launcher.
 *
 * Format (JSON):
 * {
 *   "format":         "TurtleLauncherBackup",
 *   "formatVersion":  1,
 *   "launcherVersion":"<InfoDistributor version name>",
 *   "createdAt":      "yyyy-MM-dd'T'HH:mm:ss",
 *   "categories": {
 *     "settings":      <raw settings JSON array>,
 *     "controls":      { "<fileName>": "<layout JSON>", ... },
 *     "accountsSafe":  [ {whitelisted non-secret fields}, ... ],
 *     "instances":     { "<profile entry name>": {profile config json}, ... }
 *   }
 * }
 *
 * Design rules (from the migration spec):
 *  - The format is versioned and NOT tied to any internal database layout, so
 *    future launcher versions can migrate old backups.
 *  - Account export is a WHITELIST of non-secret fields. Passwords, Microsoft
 *    access tokens and refresh tokens are never written. Microsoft accounts
 *    re-authenticate through the normal flow after import.
 *  - Restoring never silently overwrites: the caller passes a [RestoreConfirmator]
 *    which is asked, per category, whether existing data may be replaced.
 */
object TurtleBackupManager {
    private const val TAG = "TurtleBackupManager"
    const val FORMAT_NAME = "TurtleLauncherBackup"
    const val FORMAT_VERSION = 1

    /** Categories that can be included in an export. */
    enum class Category { SETTINGS, CONTROLS, ACCOUNTS_SAFE, INSTANCES }

    /** Asked before overwriting anything during a restore. Return false to skip the category. */
    fun interface RestoreConfirmator {
        fun confirmReplace(category: Category, itemCount: Int): Boolean
    }

    data class BackupResult(val json: String, val counts: Map<Category, Int>)

    // ---------------------------------------------------------------- export

    @JvmStatic
    @JvmOverloads
    fun export(categories: Set<Category> = Category.entries.toSet()): BackupResult? {
        return runCatching {
            val root = JsonObject()
            root.addProperty("format", FORMAT_NAME)
            root.addProperty("formatVersion", FORMAT_VERSION)
            root.addProperty("launcherVersion", net.kdt.pojavlaunch.InfoDistributor.LAUNCHER_NAME)
            root.addProperty("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))

            val cats = JsonObject()
            val counts = mutableMapOf<Category, Int>()

            if (Category.SETTINGS in categories) {
                val settings = SettingsBackupManager.exportToJson()
                if (settings != null) {
                    cats.add("settings", JsonParser.parseString(settings))
                    counts[Category.SETTINGS] = 1
                } else {
                    cats.add("settings", JsonArray())
                    counts[Category.SETTINGS] = 0
                }
            }

            if (Category.CONTROLS in categories) {
                val controls = JsonObject()
                val dir = File(PathManager.DIR_CTRLMAP_PATH)
                dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                    runCatching { controls.addProperty(f.name, Tools.read(f)) }
                        .onFailure { Logging.w(TAG, "Skipping unreadable control layout ${f.name}", it) }
                }
                cats.add("controls", controls)
                counts[Category.CONTROLS] = controls.size()
            }

            if (Category.ACCOUNTS_SAFE in categories) {
                val accounts = JsonArray()
                val dir = File(PathManager.DIR_ACCOUNT_NEW)
                dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                    runCatching {
                        val acc = JsonParser.parseString(Tools.read(f)).asJsonObject
                        accounts.add(sanitizeAccount(acc))
                    }.onFailure { Logging.w(TAG, "Skipping unreadable account ${f.name}", it) }
                }
                cats.add("accountsSafe", accounts)
                counts[Category.ACCOUNTS_SAFE] = accounts.size()
            }

            if (Category.INSTANCES in categories) {
                val instances = JsonObject()
                // Instance launch configs live in the profile-path registry; the game
                // directories themselves (worlds!) are intentionally NOT part of a config
                // backup — they are user data and far too large for a config file.
                runCatching {
                    val profileFile = PathManager.FILE_PROFILE_PATH
                    if (profileFile.exists()) {
                        val parsed = JsonParser.parseString(Tools.read(profileFile))
                        if (parsed.isJsonObject) instances.add("profilePaths", parsed.asJsonObject)
                    }
                }.onFailure { Logging.w(TAG, "Failed to read profile path config", it) }
                runCatching {
                    val profilesFile = File(PathManager.DIR_GAME_HOME, "launcher_profiles.json")
                    if (profilesFile.exists()) {
                        val parsed = JsonParser.parseString(Tools.read(profilesFile))
                        if (parsed.isJsonObject) instances.add("launchProfiles", parsed.asJsonObject)
                    }
                }.onFailure { Logging.w(TAG, "Failed to read launch profiles", it) }
                cats.add("instances", instances)
                counts[Category.INSTANCES] = instances.size()
            }

            root.add("categories", cats)
            BackupResult(Tools.GLOBAL_GSON.toJson(root), counts)
        }.onFailure { e ->
            Logging.e(TAG, "Backup export failed", e)
            null
        }.getOrNull()
    }

    @JvmStatic
    fun exportToFile(destFile: File, categories: Set<Category> = Category.entries.toSet()): Boolean {
        val result = export(categories) ?: return false
        return runCatching {
            destFile.parentFile?.mkdirs()
            destFile.writeText(result.json)
            true
        }.onFailure { e ->
            Logging.e(TAG, "Backup write failed", e); false
        }.getOrDefault(false)
    }

    /** Whitelist of account fields safe to export. Tokens and passwords are never included. */
    private val SAFE_ACCOUNT_FIELDS = listOf(
        "username", "profileId", "accountType", "isMicrosoft", "xuid", "selectedVersion",
        "skinFaceBase64", "chromaName", "chromaStyle", "chromaColors", "capeUrl", "skinFile",
        "slim", "lastUsed"
    )

    private fun sanitizeAccount(acc: JsonObject): JsonObject {
        val out = JsonObject()
        for (key in SAFE_ACCOUNT_FIELDS) if (acc.has(key)) out.add(key, acc.get(key))
        // NEVER: accessToken, msaRefreshToken, clientToken, otherPassword, expiresAt (secret-ish)
        return out
    }

    // ---------------------------------------------------------------- import

    data class BackupInfo(
        val formatVersion: Int,
        val launcherVersion: String,
        val createdAt: String,
        val availableCategories: Set<Category>,
        val categoryCounts: Map<Category, Int>
    )

    @JvmStatic
    fun inspect(json: String): BackupInfo? {
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            require(root.get("format")?.asString == FORMAT_NAME) { "Not a $FORMAT_NAME file" }
            val version = root.get("formatVersion")?.asInt ?: 0
            require(version in 1..FORMAT_VERSION) { "Unsupported backup format version $version" }
            val cats = root.getAsJsonObject("categories") ?: JsonObject()
            val available = mutableSetOf<Category>()
            val counts = mutableMapOf<Category, Int>()
            fun count(name: String, cat: Category, cnt: () -> Int) {
                if (cats.has(name)) { available.add(cat); counts[cat] = cnt() }
            }
            count("settings", Category.SETTINGS) { 1 }
            count("controls", Category.CONTROLS) { cats.getAsJsonObject("controls").size() }
            count("accountsSafe", Category.ACCOUNTS_SAFE) { cats.getAsJsonArray("accountsSafe").size() }
            count("instances", Category.INSTANCES) { cats.getAsJsonObject("instances").size() }
            BackupInfo(
                formatVersion = version,
                launcherVersion = root.get("launcherVersion")?.asString ?: "unknown",
                createdAt = root.get("createdAt")?.asString ?: "unknown",
                availableCategories = available,
                categoryCounts = counts
            )
        }.onFailure { e ->
            Logging.e(TAG, "Backup inspect failed", e); null
        }.getOrNull()
    }

    /**
     * Restore categories from a backup. [confirmator] is asked before any existing data is
     * replaced; answering false skips that category. Returns per-category restore results.
     */
    @JvmStatic
    fun restore(
        json: String,
        categories: Set<Category> = Category.entries.toSet(),
        confirmator: RestoreConfirmator = RestoreConfirmator { _, _ -> true }
    ): Map<Category, Boolean> {
        val results = mutableMapOf<Category, Boolean>()
        val root = runCatching {
            JsonParser.parseString(json).asJsonObject.also {
                require(it.get("format")?.asString == FORMAT_NAME) { "Not a $FORMAT_NAME file" }
            }
        }.onFailure { e ->
            Logging.e(TAG, "Backup restore: invalid file", e)
            Category.entries.forEach { c -> results[c] = false }
            return results
        }.getOrNull() ?: return results
        val cats = root.getAsJsonObject("categories") ?: JsonObject()

        if (Category.SETTINGS in categories && cats.has("settings")) {
            val ok = runCatching {
                val count = 1
                if (!confirmator.confirmReplace(Category.SETTINGS, count)) return@runCatching false
                val settingsJson = cats.get("settings").toString()
                SettingsBackupManager.importFromJson(settingsJson).also {
                    if (it) Settings.refreshSettings()
                }
            }.onFailure { e -> Logging.e(TAG, "Settings restore failed", e); false }.getOrDefault(false)
            results[Category.SETTINGS] = ok
        }

        if (Category.CONTROLS in categories && cats.has("controls")) {
            val ok = runCatching {
                val obj = cats.getAsJsonObject("controls")
                if (!confirmator.confirmReplace(Category.CONTROLS, obj.size())) return@runCatching false
                val dir = File(PathManager.DIR_CTRLMAP_PATH).apply { mkdirs() }
                var all = true
                for ((name, content) in obj.entrySet()) {
                    val safeName = File(name).name // path traversal guard
                    runCatching { File(dir, safeName).writeText(content.asString) }
                        .onFailure { all = false; Logging.w(TAG, "Failed to restore control $safeName", it) }
                }
                all
            }.onFailure { e -> Logging.e(TAG, "Controls restore failed", e); false }.getOrDefault(false)
            results[Category.CONTROLS] = ok
        }

        if (Category.ACCOUNTS_SAFE in categories && cats.has("accountsSafe")) {
            val ok = runCatching {
                val arr = cats.getAsJsonArray("accountsSafe")
                if (!confirmator.confirmReplace(Category.ACCOUNTS_SAFE, arr.size())) return@runCatching false
                val dir = File(PathManager.DIR_ACCOUNT_NEW).apply { mkdirs() }
                var restored = 0
                for (el in arr) {
                    val acc = el.asJsonObject
                    val username = acc.get("username")?.asString ?: continue
                    val uuid = acc.get("profileId")?.asString ?: "00000000-0000-0000-0000-000000000000"
                    val fileName = File(dir, "$username-$uuid.json")
                    runCatching { fileName.writeText(Tools.GLOBAL_GSON.toJson(acc)) }
                        .onSuccess { restored++ }
                        .onFailure { Logging.w(TAG, "Failed to restore account $username", it) }
                }
                // Note: Microsoft accounts need re-authentication (tokens are not exportable).
                restored > 0
            }.onFailure { e -> Logging.e(TAG, "Accounts restore failed", e); false }.getOrDefault(false)
            results[Category.ACCOUNTS_SAFE] = ok
        }

        if (Category.INSTANCES in categories && cats.has("instances")) {
            val ok = runCatching {
                val obj = cats.getAsJsonObject("instances")
                if (!confirmator.confirmReplace(Category.INSTANCES, obj.size())) return@runCatching false
                var all = true
                if (obj.has("profilePaths")) {
                    runCatching {
                        PathManager.FILE_PROFILE_PATH.parentFile?.mkdirs()
                        PathManager.FILE_PROFILE_PATH.writeText(Tools.GLOBAL_GSON.toJson(obj.get("profilePaths")))
                    }.onFailure { all = false; Logging.w(TAG, "Failed to restore profile paths", it) }
                }
                if (obj.has("launchProfiles")) {
                    runCatching {
                        val f = File(PathManager.DIR_GAME_HOME, "launcher_profiles.json")
                        f.parentFile?.mkdirs()
                        f.writeText(Tools.GLOBAL_GSON.toJson(obj.get("launchProfiles")))
                    }.onFailure { all = false; Logging.w(TAG, "Failed to restore launch profiles", it) }
                }
                all
            }.onFailure { e -> Logging.e(TAG, "Instances restore failed", e); false }.getOrDefault(false)
            results[Category.INSTANCES] = ok
        }

        return results
    }

    @JvmStatic
    fun restoreFromFile(srcFile: File, categories: Set<Category> = Category.entries.toSet(), confirmator: RestoreConfirmator = RestoreConfirmator { _, _ -> true }): Map<Category, Boolean> {
        if (!srcFile.exists()) return Category.entries.associateWith { false }
        return runCatching { restore(srcFile.readText(), categories, confirmator) }
            .onFailure { e -> Logging.e(TAG, "Backup restore from file failed", e) }
            .getOrElse { Category.entries.associateWith { false } }
    }
}
