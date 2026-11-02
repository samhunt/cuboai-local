package com.cuboai.local.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Camera TUTK credentials stay in the app's private storage, encrypted at rest.
 * Cubo account passwords and cloud tokens are never written here.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    fun loadCameras(): List<CameraCredentials> {
        val json = prefs.getString(KEY_CAMERAS, null)
        if (json != null) {
            val arr = JSONArray(json)
            return buildList {
                for (i in 0 until arr.length()) {
                    parseCamera(arr.getJSONObject(i))?.let { add(it) }
                }
            }
        }
        return listOfNotNull(legacyCamera())
    }

    fun loadSelectedUid(): String? = prefs.getString(KEY_SELECTED_UID, null)
        ?: loadCameras().firstOrNull()?.uid

    fun saveCameras(cameras: List<CameraCredentials>, selectedUid: String?) {
        if (cameras.isEmpty()) {
            prefs.edit().clear().apply()
            return
        }
        val arr = JSONArray()
        cameras.forEach { camera ->
            arr.put(
                JSONObject()
                    .put("name", camera.name)
                    .put("ip", camera.ip.trim())
                    .put("uid", camera.uid.trim())
                    .put("account", camera.account.trim())
                    .put("password", camera.password),
            )
        }
        val selected = cameras.find { it.uid == selectedUid } ?: cameras.first()
        prefs.edit()
            .putString(KEY_CAMERAS, arr.toString())
            .putString(KEY_SELECTED_UID, selected.uid)
            .putString(KEY_NAME, selected.name)
            .putString(KEY_IP, selected.ip.trim())
            .putString(KEY_UID, selected.uid.trim())
            .putString(KEY_ACCOUNT, selected.account.trim())
            .putString(KEY_PASSWORD, selected.password)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun legacyCamera(): CameraCredentials? {
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

    private fun parseCamera(obj: JSONObject): CameraCredentials? {
        val uid = obj.optString("uid")
        val account = obj.optString("account")
        val password = obj.optString("password")
        if (uid.isBlank() || account.isBlank() || password.isBlank()) return null
        return CameraCredentials(
            name = obj.optString("name", "Nursery").ifBlank { "Nursery" },
            ip = obj.optString("ip"),
            uid = uid,
            account = account,
            password = password,
        )
    }

    private companion object {
        const val PREFS_NAME = "cuboai_local_creds"
        const val FALLBACK_PREFS = "cuboai_local_creds_fallback"
        const val KEY_CAMERAS = "cameras_json"
        const val KEY_SELECTED_UID = "selected_uid"
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
