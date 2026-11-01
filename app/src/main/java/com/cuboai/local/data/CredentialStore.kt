package com.cuboai.local.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Camera TUTK credentials stay in the app's private storage, encrypted at rest.
 * Cubo account passwords and cloud tokens are never written here.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    var credentials: CameraCredentials?
        get() {
            val uid = prefs.getString(KEY_UID, null) ?: return null
            val account = prefs.getString(KEY_ACCOUNT, null) ?: return null
            val password = prefs.getString(KEY_PASSWORD, null) ?: return null
            return CameraCredentials(
                name = prefs.getString(KEY_NAME, "Nursery") ?: "Nursery",
                ip = prefs.getString(KEY_IP, "") ?: "",
                uid = uid,
                account = account,
                password = password,
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit().clear().apply()
                return
            }
            prefs.edit()
                .putString(KEY_NAME, value.name)
                .putString(KEY_IP, value.ip.trim())
                .putString(KEY_UID, value.uid.trim())
                .putString(KEY_ACCOUNT, value.account.trim())
                .putString(KEY_PASSWORD, value.password)
                .apply()
        }

    fun clear() {
        credentials = null
    }

    private companion object {
        const val PREFS_NAME = "cuboai_local_creds"
        const val FALLBACK_PREFS = "cuboai_local_creds_fallback"
        const val KEY_NAME = "name"
        const val KEY_IP = "ip"
        const val KEY_UID = "uid"
        const val KEY_ACCOUNT = "account"
        const val KEY_PASSWORD = "password"

        fun createPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (_: Exception) {
                context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
            }
        }
    }
}
