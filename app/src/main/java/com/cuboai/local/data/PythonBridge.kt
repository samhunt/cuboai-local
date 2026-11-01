package com.cuboai.local.data

import com.chaquo.python.Python
import org.json.JSONObject

class PythonBridge {
    private val module by lazy { Python.getInstance().getModule("bridge") }

    fun connect(creds: CameraCredentials): Result<Unit> =
        call("connect", creds.uid, creds.account, creds.password, creds.ip)

    fun disconnect(): Result<Unit> = call("disconnect")

    fun startStreamServer(): Result<String> {
        val json = invoke("start_stream_server")
        return if (json.optBoolean("ok")) {
            Result.success(json.optString("url"))
        } else {
            Result.failure(IllegalStateException(json.optString("error", "Stream failed")))
        }
    }

    fun stopStream(): Result<Unit> = call("stop_stream")

    fun sensors(): Result<CameraStatus> {
        val json = invoke("sensors")
        if (!json.optBoolean("ok")) {
            return Result.failure(IllegalStateException(json.optString("error", "Status failed")))
        }
        return Result.success(
            CameraStatus(
                temperatureC = json.optDoubleOrNull("temperature_c"),
                humidityPct = json.optDoubleOrNull("humidity_pct"),
                firmware = json.optStringOrNull("firmware"),
                wifiQuality = json.optIntOrNull("wifi_quality"),
                wifiSsid = json.optStringOrNull("wifi_ssid"),
                nightLightOn = json.optBooleanOrNull("night_light_on"),
                brightness = json.optIntOrNull("brightness"),
                sleepMode = json.optBooleanOrNull("sleep_mode"),
                lullabyPlaying = json.optBooleanOrNull("lullaby_playing"),
                lullabyName = json.optStringOrNull("lullaby_name"),
                lullabyVolume = json.optIntOrNull("lullaby_volume"),
                speakerLevel = json.optIntOrNull("speaker_level"),
                nightVision = json.optStringOrNull("night_vision"),
            ),
        )
    }

    fun setNightLight(on: Boolean) = call("set_night_light", on)
    fun setBrightness(value: Int) = call("set_brightness", value)
    fun setSleepMode(enabled: Boolean) = call("set_sleep_mode", enabled)
    fun setLullaby(uuid: String) = call("set_lullaby", uuid)
    fun stopLullaby() = call("stop_lullaby")

    fun lullabies(): Result<List<LullabySong>> {
        val json = invoke("lullabies")
        if (!json.optBoolean("ok")) {
            return Result.failure(IllegalStateException(json.optString("error", "Catalog failed")))
        }
        val array = json.optJSONArray("songs") ?: return Result.success(emptyList())
        val songs = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    LullabySong(
                        uuid = item.optString("uuid"),
                        name = item.optString("name"),
                        category = item.optString("category"),
                    ),
                )
            }
        }
        return Result.success(songs)
    }

    private fun call(name: String, vararg args: Any?): Result<Unit> {
        val json = invoke(name, *args)
        return if (json.optBoolean("ok")) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(json.optString("error", "Command failed")))
        }
    }

    private fun invoke(name: String, vararg args: Any?): JSONObject {
        val raw = module.callAttr(name, *args).toString()
        return JSONObject(raw)
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (isNull(key) || !has(key)) null else optBoolean(key)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }
