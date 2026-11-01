package com.cuboai.local.data

data class CameraCredentials(
    val name: String = "Nursery",
    val ip: String = "",
    val uid: String = "",
    val account: String = "",
    val password: String = "",
)

enum class LoginMode { CuboAccount, Manual }

data class CloudCamera(
    val deviceId: String,
    val name: String,
    val uid: String,
    val account: String,
    val password: String,
    val ip: String = "",
)

sealed class CloudAuthResult {
    data class Cameras(val cameras: List<CloudCamera>) : CloudAuthResult()
    data class MfaRequired(val hint: String) : CloudAuthResult()
}

data class LullabySong(
    val uuid: String,
    val name: String,
    val category: String,
)

data class CameraStatus(
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val firmware: String? = null,
    val wifiQuality: Int? = null,
    val wifiSsid: String? = null,
    val nightLightOn: Boolean? = null,
    val brightness: Int? = null,
    val sleepMode: Boolean? = null,
    val lullabyPlaying: Boolean? = null,
    val lullabyName: String? = null,
    val lullabyVolume: Int? = null,
    val speakerLevel: Int? = null,
    val nightVision: String? = null,
)

sealed interface Screen {
    data object Login : Screen
    data object Monitor : Screen
    data class License(val from: Screen) : Screen
}
