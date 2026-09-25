package kr.co.donghyun.flamelauncher.data.auth


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AuthSession(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long  // System.currentTimeMillis() + expires_in * 1000
)

private data class MsTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("expires_in") val expiresIn: Long
)

private data class XblResponse(
    @SerializedName("Token") val token: String,
    @SerializedName("DisplayClaims") val displayClaims: Map<String, Any>
)

private data class XstsResponse(
    @SerializedName("Token") val token: String,
    @SerializedName("DisplayClaims") val displayClaims: Map<String, Any>
)

private data class McLoginResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Long
)

private data class McProfileResponse(
    val id: String? = null,
    val name: String? = null,
    val error: String? = null,
    val errorMessage: String? = null
)

object MicrosoftAuthManager {
    const val CLIENT_ID = "00000000402b5328"  // Xbox 공식 Client ID
    const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
    const val SCOPE = "XboxLive.signin offline_access"

    private const val PREFS_NAME = "ms_auth"
    private const val KEY_SESSION = "session"

    private val client = OkHttpClient()
    private val gson = Gson()

    fun getAuthUrl(): String {
        return "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=$CLIENT_ID" +
                "&response_type=code" +
                "&redirect_uri=$REDIRECT_URI" +
                "&scope=XboxLive.signin%20offline_access" +
                "&prompt=select_account"
    }

    fun loadSession(context: Context): AuthSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            gson.fromJson(json, AuthSession::class.java)
        } catch (_: Exception) { null }
    }

    fun saveSession(context: Context, session: AuthSession) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSION, gson.toJson(session)).apply()
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSION).apply()
    }

    fun isSessionValid(session: AuthSession): Boolean {
        return System.currentTimeMillis() < session.expiresAt - 60_000
    }

    // 인증 코드로 전체 로그인 수행
    fun loginWithCode(code: String): AuthSession {
        Log.d("FLAME_LAUNCHER", "1. MS 토큰 요청")
        val msToken = getMsToken(code)
        Log.d("FLAME_LAUNCHER", "2. XBL 토큰 요청")
        val (xblToken, xblUhs) = getXblToken(msToken.accessToken)
        Log.d("FLAME_LAUNCHER", "3. XSTS 토큰 요청, uhs=$xblUhs")
        val xstsToken = getXstsToken(xblToken)
        Log.d("FLAME_LAUNCHER", "4. MC 토큰 요청")
        val mcToken = getMcToken(xblUhs, xstsToken)
        Log.d("FLAME_LAUNCHER", "MC 토큰: ${mcToken.accessToken.take(20)}...")
        Log.d("FLAME_LAUNCHER", "5. 프로필 요청")
        val profile = getMcProfile(mcToken.accessToken)
        Log.d("FLAME_LAUNCHER", "6. 완료: ${profile.name}")

        return AuthSession(
            username = profile.name ?: throw Exception("프로필 이름 없음"),
            uuid = profile.id ?: throw Exception("프로필 UUID 없음"),
            accessToken = mcToken.accessToken,
            refreshToken = msToken.refreshToken ?: "",
            expiresAt = System.currentTimeMillis() + mcToken.expiresIn * 1000
        )
    }

    // refresh token으로 갱신
    fun refreshSession(refreshToken: String): AuthSession {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", SCOPE)
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(body)
            .build()

        val msToken = client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("MS token refresh 실패")
            gson.fromJson(json, MsTokenResponse::class.java)
        }

        val (xblToken, xblUhs) = getXblToken(msToken.accessToken)
        val xstsToken = getXstsToken(xblToken)
        val mcToken = getMcToken(xblUhs, xstsToken)

        Log.d("FLAME_LAUNCHER", "MC 토큰: ${mcToken.accessToken.take(20)}...")

        val profile = getMcProfile(mcToken.accessToken)

        return AuthSession(
            username = profile.name ?: throw Exception("프로필 이름 없음"),
            uuid = profile.id ?: throw Exception("프로필 UUID 없음"),
            accessToken = mcToken.accessToken,
            refreshToken = msToken.refreshToken ?: refreshToken,
            expiresAt = System.currentTimeMillis() + mcToken.expiresIn * 1000
        )
    }

    private fun getMsToken(code: String): MsTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", SCOPE)
            .build()

        val request = Request.Builder()
            .url("https://login.live.com/oauth20_token.srf")
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("MS token 실패")
            Log.d("FLAME_LAUNCHER", "MS 토큰 응답: $json")
            gson.fromJson(json, MsTokenResponse::class.java)
        }
    }

    private data class XblResponseFull(
        @SerializedName("Token") val token: String,
        @SerializedName("DisplayClaims") val displayClaims: Map<String, Any>
    ) {
        fun getUhs(): String {
            @Suppress("UNCHECKED_CAST")
            val xui = (displayClaims["xui"] as? List<Map<String, Any>>)
            return xui?.firstOrNull()?.get("uhs") as? String ?: ""
        }
    }

    private fun getXblToken(msAccessToken: String): Pair<String, String> {
        val bodyJson = """
        {
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": "d=$msAccessToken"
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT"
        }
    """.trimIndent()

        val request = Request.Builder()
            .url("https://user.auth.xboxlive.com/user/authenticate")
            .post(bodyJson.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("XBL 실패")
            Log.d("FLAME_LAUNCHER", "XBL 응답: $json")
            val xbl = gson.fromJson(json, XblResponseFull::class.java)
            Log.d("FLAME_LAUNCHER", "XBL 파싱: token=${xbl?.token}, uhs=${xbl?.getUhs()}")
            Pair(xbl.token, xbl.getUhs())
        }
    }

    private fun getXstsToken(xblToken: String): String {
        val bodyJson = """
        {
            "Properties": {
                "SandboxId": "RETAIL",
                "UserTokens": ["$xblToken"]
            },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT"
        }
    """.trimIndent()

        val request = Request.Builder()
            .url("https://xsts.auth.xboxlive.com/xsts/authorize")
            .post(bodyJson.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("XSTS 실패")
            val xsts = gson.fromJson(json, XstsResponse::class.java)
            xsts.token
        }
    }

    private fun getMcToken(xblUhs: String, xstsToken: String): McLoginResponse {
        val bodyJson = """{"identityToken": "XBL3.0 x=$xblUhs;$xstsToken"}"""
        Log.d("FLAME_LAUNCHER", "MC 토큰 요청 body: $bodyJson")

        val request = Request.Builder()
            .url("https://api.minecraftservices.com/authentication/login_with_xbox")
            .post(bodyJson.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("MC 로그인 실패")
            Log.d("FLAME_LAUNCHER", "MC 토큰 응답: $json")
            gson.fromJson(json, McLoginResponse::class.java)
        }
    }

    private fun getMcProfile(mcAccessToken: String): McProfileResponse {
        val request = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .header("Authorization", "Bearer $mcAccessToken")
            .build()

        return client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("프로필 조회 실패")
            Log.d("FLAME_LAUNCHER", "프로필 응답: $json")
            val profile = gson.fromJson(json, McProfileResponse::class.java)
            if (profile.name == null) {
                throw Exception("Minecraft 프로필 없음 - 게임을 구매하지 않은 계정일 수 있습니다")
            }
            profile
        }
    }
}