package kr.co.donghyun.turtlelauncher.data.auth

import android.content.Context
import com.google.gson.Gson
import java.util.UUID

/**
 * Offline account storage compatible with Turtle's local account behavior.
 *
 * The UUID is stable for a chosen name and the account never receives a fake
 * Microsoft token. MinecraftActivity therefore sends a clearly offline session
 * instead of pretending that a network login succeeded.
 */
data class LocalAccount(
    val username: String,
    val uuid: String,
)

object LocalAccountManager {
    private const val PREFS = "local_account"
    private const val KEY = "account"
    private val gson = Gson()

    fun load(context: Context): LocalAccount? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.let { gson.fromJson(it, LocalAccount::class.java) }
    }.getOrNull()

    fun save(context: Context, username: String): LocalAccount {
        val clean = username.trim().take(16)
        require(clean.length in 3..16 && clean.all { it.isLetterOrDigit() || it == '_' }) {
            "Offline username must be 3-16 letters, numbers, or underscores"
        }
        val account = LocalAccount(
            username = clean,
            uuid = UUID.nameUUIDFromBytes("TurtleOfflinePlayer:$clean".toByteArray()).toString(),
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(account)).apply()
        return account
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
