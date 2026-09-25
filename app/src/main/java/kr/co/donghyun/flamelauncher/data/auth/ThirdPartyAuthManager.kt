package kr.co.donghyun.flamelauncher.data.auth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Login and launch state for authlib-injector/Yggdrasil accounts.
 *
 * This is deliberately separate from MicrosoftAuthManager: third-party servers
 * issue a Minecraft access token and profile directly, while Microsoft uses the
 * Xbox/Minecraft Services exchange. Passwords are only used for the login/refresh
 * request and are never placed in JVM arguments or logs.
 */
data class ThirdPartyLoginRequest(
    val providerId: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val displayName: String,
    val registerUrl: String? = null,
)

data class ThirdPartyAccount(
    val providerId: String,
    val displayName: String,
    /** Authlib-injector API root, for example https://authserver.ely.by. */
    val serverUrl: String,
    val username: String,
    val password: String,
    val accessToken: String,
    val clientToken: String,
    /** Canonical UUID with hyphens for Minecraft command-line arguments. */
    val profileId: String,
    val profileName: String,
    val registerUrl: String? = null,
)

object ThirdPartyAuthManager {
    private const val TAG = "FlameThirdPartyAuth"
    private const val PREFS = "third_party_auth"
    private const val KEY_ACCOUNT = "account"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    const val ELY_BY = "ely_by"
    const val BATLLY = "battly"
    const val CUSTOM = "custom"

    const val ELY_BY_URL = "https://authserver.ely.by"
    const val ELY_BY_REGISTER_URL = "https://ely.by"
    const val BATLLY_URL = "https://api.battlylauncher.com"
    const val BATLLY_REGISTER_URL = "https://battlylauncher.com"

    fun load(context: Context): ThirdPartyAccount? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCOUNT, null)
            ?.let { gson.fromJson(it, ThirdPartyAccount::class.java) }
            ?.takeIf { it.serverUrl.isNotBlank() && it.profileId.isNotBlank() }
    }.getOrNull()

    fun save(context: Context, account: ThirdPartyAccount) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCOUNT, gson.toJson(account)).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ACCOUNT).apply()
    }

    /**
     * Logs in to a standard authlib-injector/Yggdrasil server.
     * This function performs blocking IO and must be called from Dispatchers.IO.
     */
    fun login(request: ThirdPartyLoginRequest): ThirdPartyAccount {
        val inputUrl = normalizeUrl(request.serverUrl)
        val apiUrl = discoverApiLocation(inputUrl)
        val clientToken = UUID.randomUUID().toString()
        val body = JsonObject().apply {
            addProperty("username", request.username.trim())
            addProperty("password", request.password)
            addProperty("requestUser", true)
            addProperty("clientToken", clientToken)
            add("agent", JsonObject().apply {
                addProperty("name", "Minecraft")
                addProperty("version", 1)
            })
        }
        val result = post(apiUrl + "/authserver/authenticate", body)
        val profile = selectedProfile(result)
            ?: throw IOException("The account server returned no Minecraft profile")
        val profileId = canonicalUuid(profile.get("id")?.asString)
            ?: throw IOException("The account server returned an invalid profile UUID")
        val profileName = profile.get("name")?.asString?.takeIf { it.isNotBlank() }
            ?: request.username.trim()
        val accessToken = result.get("accessToken")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("The account server returned no access token")

        return ThirdPartyAccount(
            providerId = request.providerId,
            displayName = request.displayName,
            serverUrl = apiUrl,
            username = request.username.trim(),
            password = request.password,
            accessToken = accessToken,
            clientToken = result.get("clientToken")?.asString?.takeIf { it.isNotBlank() } ?: clientToken,
            profileId = profileId,
            profileName = profileName,
            registerUrl = request.registerUrl,
        )
    }

    /**
     * Refreshes a cached Yggdrasil token when possible. A cached token is kept
     * if the provider is temporarily unreachable, so offline-compatible servers
     * do not become unlaunchable just because refresh is down.
     */
    fun refreshIfPossible(context: Context, account: ThirdPartyAccount): ThirdPartyAccount {
        if (account.accessToken.isBlank() || account.clientToken.isBlank()) return account
        return runCatching {
            val body = JsonObject().apply {
                addProperty("accessToken", account.accessToken)
                addProperty("clientToken", account.clientToken)
                add("selectedProfile", JsonObject().apply {
                    addProperty("id", account.profileId.replace("-", ""))
                    addProperty("name", account.profileName)
                })
            }
            val result = post(account.serverUrl + "/authserver/refresh", body)
            val profile = selectedProfile(result)
            val refreshed = account.copy(
                accessToken = result.get("accessToken")?.asString?.takeIf { it.isNotBlank() }
                    ?: account.accessToken,
                clientToken = result.get("clientToken")?.asString?.takeIf { it.isNotBlank() }
                    ?: account.clientToken,
                profileId = canonicalUuid(profile?.get("id")?.asString) ?: account.profileId,
                profileName = profile?.get("name")?.asString?.takeIf { it.isNotBlank() }
                    ?: account.profileName,
            )
            save(context, refreshed)
            refreshed
        }.onFailure {
            Log.w(TAG, "Third-party token refresh failed; using cached token", it)
        }.getOrDefault(account)
    }

    /**
     * JVM arguments required by authlib-injector. Battly uses its own compatible
     * agent build; ely.by and custom Yggdrasil servers use the bundled agent.
     */
    fun jvmArgs(context: Context, account: ThirdPartyAccount, bundledAuthlib: java.io.File): Array<String> {
        val authlib = if (account.providerId == BATLLY) {
            BattlyAuthlibManager.ensureAuthlib(context, bundledAuthlib)
        } else {
            bundledAuthlib
        }
        if (!authlib.isFile || authlib.length() == 0L) return emptyArray()

        val args = mutableListOf<String>()
        if (account.providerId == BATLLY || isBattlyServer(account.serverUrl)) {
            args += "-Dbattly.api.url=$BATLLY_URL"
            args += "-javaagent:${authlib.absolutePath}=$BATLLY_URL"
            // Battly's launcher uses this arrangement for Java 8-25 runtimes.
            args += "-Xbootclasspath/a:${authlib.absolutePath}"
        } else {
            args += "-javaagent:${authlib.absolutePath}=${account.serverUrl}"
        }
        Log.i(TAG, "Attached ${account.displayName} authlib")
        return args.toTypedArray()
    }

    fun isBattlyServer(url: String): Boolean =
        url.contains("battlylauncher.com", ignoreCase = true)

    fun normalizeUrl(raw: String): String {
        var value = raw.trim()
        require(value.isNotEmpty()) { "Authentication server URL is required" }
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) value = "https://$value"
        value = value.removeSuffix("/")
        val uri = URI(value)
        require(uri.host != null && uri.host!!.isNotBlank()) { "Authentication server URL is invalid" }
        require(uri.userInfo == null) { "Authentication server URL must not contain credentials" }
        return value
    }

    private fun discoverApiLocation(input: String): String {
        return runCatching {
            val request = Request.Builder().url(input).get().build()
            client.newCall(request).execute().use { response ->
                val location = response.header("x-authlib-injector-api-location")
                    ?: response.header("X-Authlib-Injector-Api-Location")
                if (location.isNullOrBlank()) input
                else normalizeUrl(URI(input).resolve(location).toString())
            }
        }.getOrDefault(input)
    }

    private fun post(url: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(normalizeUrl(url))
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.get("errorMessage")?.asString
                    ?: json?.get("message")?.asString
                    ?: json?.get("error")?.asString
                    ?: "HTTP ${response.code}"
                throw IOException(message)
            }
            return json ?: throw IOException("The account server returned invalid JSON")
        }
    }

    private fun selectedProfile(result: JsonObject): JsonObject? {
        result.getAsJsonObject("selectedProfile")?.let { return it }
        val profiles: JsonArray = result.getAsJsonArray("availableProfiles") ?: return null
        return profiles.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun canonicalUuid(raw: String?): String? {
        val value = raw?.replace("-", "")?.trim()?.lowercase() ?: return null
        if (value.length != 32 || value.any { it !in "0123456789abcdef" }) return null
        return value.chunked(8).joinToString("-")
    }
}
