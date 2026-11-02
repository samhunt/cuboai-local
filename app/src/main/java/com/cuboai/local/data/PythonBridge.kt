package com.cuboai.local.data

import android.util.Log
import com.chaquo.python.Python
import org.json.JSONObject
import kotlin.concurrent.thread

class PythonBridge {
    private val module by lazy { Python.getInstance().getModule("bridge") }
    private val muxLock = Any()
    private var muxJavaThread: Thread? = null

    fun connect(creds: CameraCredentials): Result<Unit> =
        call("connect", creds.uid, creds.account, creds.password, creds.ip)

    fun disconnect(): Result<Unit> {
        TsHub.INSTANCE.running = false
        TsHub.INSTANCE.clear()
        return call("disconnect")
    }

    fun startStreamServer(): Result<String> {
        val hub = TsHub.INSTANCE
        hub.clear()
        hub.running = true
        val json = invoke("start_stream_server")
        if (!json.optBoolean("ok")) {
            hub.running = false
            return Result.failure(IllegalStateException(json.optString("error", "Stream failed")))
        }
        invoke("set_ts_hub", hub)
        synchronized(muxLock) {
            if (muxJavaThread?.isAlive != true) {
                muxJavaThread = thread(name = "cuboai-mux", isDaemon = true) {
                    try {
                        Log.i("cuboai-mux", "run_mux_loop enter")
                        module.callAttr("set_ts_hub", TsHub.INSTANCE)
                        module.callAttr("run_mux_loop")
                        Log.i("cuboai-mux", "run_mux_loop returned")
                    } catch (err: Exception) {
                        Log.e("cuboai-mux", "run_mux_loop failed", err)
                    }
                }
            }
        }
        repeat(60) { i ->
            val available = hub.available()
            if (available >= 80_000) {
                Log.i("cuboai-mux", "ts ready bytes=$available")
                return Result.success("cuboai://live.ts")
            }
            if (i % 5 == 4) {
                Log.i("cuboai-mux", "waiting for ts bytes=$available")
            }
            Thread.sleep(200)
        }
        val leftover = hub.available()
        if (leftover >= 188 * 10) {
            Log.i("cuboai-mux", "ts partial bytes=$leftover")
            return Result.success("cuboai://live.ts")
        }
        hub.running = false
        Log.w("cuboai-mux", "no video bytes leftover=$leftover")
        return Result.failure(IllegalStateException("No video bytes from the camera yet."))
    }

    fun stopStream(): Result<Unit> {
        TsHub.INSTANCE.running = false
        return call("stop_stream")
    }

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
