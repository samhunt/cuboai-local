package com.cuboai.local.data

import android.content.Context
import android.util.Base64
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUser
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler
import com.amazonaws.regions.Regions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * One-time Cubo account login using the AWS Cognito Android SDK, then Cubo's
 * camera list API. Tokens are discarded afterwards; only TUTK camera fields
 * are returned.
 */
class CuboCloudClient(context: Context) {
    private val appContext = context.applicationContext
    private val pool = CognitoUserPool(
        appContext,
        POOL_ID,
        CLIENT_ID,
        CLIENT_SECRET,
        Regions.US_EAST_1,
    )
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private var email: String = ""
    private var password: String = ""
    private var user: CognitoUser? = null
    private var mfaContinuation: MultiFactorAuthenticationContinuation? = null
    private var pending: Continuation<CognitoStep>? = null

    private val handler = object : AuthenticationHandler {
        override fun onSuccess(userSession: CognitoUserSession?, newDevice: CognitoDevice?) {
            password = ""
            val access = userSession?.accessToken?.jwtToken
            val idToken = userSession?.idToken?.jwtToken
            if (access.isNullOrBlank() || idToken.isNullOrBlank()) {
                fail(IllegalStateException("Cognito did not return session tokens."))
            } else {
                resume(CognitoStep.Session(access, idToken))
            }
        }

        override fun getAuthenticationDetails(
            authenticationContinuation: AuthenticationContinuation?,
            userId: String?,
        ) {
            val continuation = authenticationContinuation ?: return fail(
                IllegalStateException("Cognito login did not complete."),
            )
            continuation.setAuthenticationDetails(
                com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails(
                    email,
                    password,
                    null,
                ),
            )
            continuation.continueTask()
        }

        override fun getMFACode(continuation: MultiFactorAuthenticationContinuation?) {
            password = ""
            if (continuation == null) {
                fail(IllegalStateException("Cognito asked for a code but sent no continuation."))
                return
            }
            mfaContinuation = continuation
            resume(CognitoStep.Mfa(mfaHint(continuation)))
        }

        override fun authenticationChallenge(continuation: ChallengeContinuation?) {
            fail(IllegalStateException(unsupportedChallenge(continuation?.challengeName)))
        }

        override fun onFailure(exception: Exception?) {
            fail(IllegalStateException(friendlyCognito(exception)))
        }
    }

    suspend fun signIn(email: String, password: String): CloudAuthResult {
        val trimmed = email.trim()
        if (trimmed.isEmpty() || password.isEmpty()) {
            error("Cubo email and password are required.")
        }
        this.email = trimmed
        this.password = password
        mfaContinuation = null
        val cognitoUser = pool.getUser(trimmed)
        user = cognitoUser
        runCatching { cognitoUser.signOut() }
        val step = awaitCognito { cognitoUser.getSessionInBackground(handler) }
        return finish(step)
    }

    suspend fun completeMfa(code: String): CloudAuthResult {
        val continuation = mfaContinuation
            ?: error("No verification is waiting. Fetch cameras again.")
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            error("Enter the verification code from SMS or your authenticator app.")
        }
        val step = awaitCognito {
            continuation.setMfaCode(trimmed)
            continuation.continueTask()
        }
        return finish(step)
    }

    private suspend fun finish(step: CognitoStep): CloudAuthResult = when (step) {
        is CognitoStep.Mfa -> CloudAuthResult.MfaRequired(step.hint)
        is CognitoStep.Session -> try {
            val cameras = loadCameras(email, step.accessToken, step.idToken)
            CloudAuthResult.Cameras(cameras)
        } finally {
            password = ""
            runCatching { user?.signOut() }
        }
    }

    private suspend fun awaitCognito(start: () -> Unit): CognitoStep =
        suspendCoroutine { cont ->
            pending = cont
            try {
                start()
            } catch (exc: Exception) {
                fail(IllegalStateException(friendlyCognito(exc)))
            }
        }

    private fun resume(step: CognitoStep) {
        val cont = pending ?: return
        pending = null
        cont.resume(step)
    }

    private fun fail(error: Throwable) {
        password = ""
        val cont = pending ?: return
        pending = null
        cont.resumeWithException(error)
    }

    private fun loadCameras(email: String, accessToken: String, idToken: String): List<CloudCamera> {
        val uuid = jwtSub(idToken)
        val cubo = cuboMobileLogin(uuid, email, accessToken)
        val cuboAccess = cubo.optString("access_token")
        if (cuboAccess.isBlank()) {
            error("Cubo login did not return an access token.")
        }
        val cameras = parseCameras(getCameras(cuboAccess))
        if (cameras.isEmpty()) {
            error("No cameras on this Cubo account.")
        }
        return cameras
    }

    private fun cuboMobileLogin(uuid: String, username: String, accessToken: String): JSONObject {
        val payload = JSONObject()
            .put("version", "2396")
            .put("lang", "en")
            .put("mobile_uuid", uuid)
            .put("provider", "Yun")
            .put("push_token", "dummy-token")
            .put("timezone", 0)
            .put("tp", "Android")
            .put("uid_p", uuid)
            .put("uname_p", username)
            .put("device_model", "sdk_gphone64_x86_64")
            .put("zone_name", "GMT")
            .toString()
        val data = httpJson(
            "POST",
            "$MOBILE_API/v2/user/login",
            mapOf(
                "User-Agent" to USER_AGENT,
                "Accept-Encoding" to "identity",
                "Content-Type" to "application/json; charset=UTF-8",
                "x-cb-authorization" to "Bearer $accessToken",
                "x-cspp-authorization" to "",
                "x-refresh-authorization" to "",
            ),
            payload,
        )
        val inner = data.optJSONObject("data") ?: data
        return inner
    }

    private fun getCameras(accessToken: String): JSONObject = httpJson(
        "GET",
        "$API_BASE/user/cameras",
        mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Encoding" to "identity",
            "Content-Type" to "application/json",
            "x-cspp-authorization" to "Bearer $accessToken",
        ),
    )

    private fun httpJson(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): JSONObject {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toRequestBody(JSON)
        builder.method(method, requestBody)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(friendlyHttp(response.code, text))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private companion object {
        const val CLIENT_ID = "1gvbkmngl920rtp6hlbp6057ue"
        const val CLIENT_SECRET = "1ot7h8m3t83g0g4b7ais7ilcf12o44cvr9cbgad0t90kcpno56jr"
        const val POOL_ID = "us-east-1_Wr7vffd5Y"
        const val MOBILE_API = "https://mobile-api.getcubo.com"
        const val API_BASE = "https://api.getcubo.com/prod"
        const val USER_AGENT = "okhttp/4.12.0"
        val JSON = "application/json; charset=UTF-8".toMediaType()

        fun mfaHint(continuation: MultiFactorAuthenticationContinuation): String {
            val details = continuation.parameters
            val destination = details?.destination.orEmpty()
            val medium = details?.deliveryMedium.orEmpty()
            return when {
                destination.contains("Time-based", ignoreCase = true) -> "authenticator app"
                medium.contains("sms", ignoreCase = true) -> "SMS"
                medium.contains("totp", ignoreCase = true) -> "authenticator app"
                else -> "SMS or authenticator"
            }
        }

        fun jwtSub(idToken: String): String {
            val payload = idToken.split('.').getOrNull(1)
                ?: error("Could not read the Cognito account id.")
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = JSONObject(
                String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)),
            )
            return json.optString("sub").ifBlank { error("Could not read the Cognito account id.") }
        }

        fun parseCameras(root: JSONObject): List<CloudCamera> {
            val profileByDevice = mutableMapOf<String, Pair<JSONObject, JSONObject>>()
            val profiles = root.optJSONArray("profiles") ?: JSONArray()
            for (i in 0 until profiles.length()) {
                val profile = profiles.optJSONObject(i) ?: continue
                val deviceId = profile.optString("device_id")
                if (deviceId.isBlank()) continue
                val parsed = try {
                    JSONObject(profile.optString("profile", "{}").ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }
                profileByDevice[deviceId] = profile to parsed
            }
            val seen = mutableSetOf<String>()
            val cameras = mutableListOf<CloudCamera>()
            val devices = root.optJSONArray("data") ?: JSONArray()
            fun add(deviceId: String, device: JSONObject, profile: JSONObject, parsed: JSONObject) {
                val uid = device.optString("license_id").trim()
                val account = device.optString("dev_admin_id").trim()
                val password = device.optString("dev_admin_pwd")
                if (uid.isBlank() || account.isBlank() || password.isBlank()) return
                val baby = parsed.optString("baby").trim()
                val name = if (baby.isNotBlank() && !baby.equals("unknown", ignoreCase = true)) baby else deviceId
                cameras += CloudCamera(
                    deviceId = deviceId,
                    name = name,
                    uid = uid,
                    account = account,
                    password = password,
                    ip = cameraIp(device, profile, parsed),
                )
            }
            for (i in 0 until devices.length()) {
                val device = devices.optJSONObject(i) ?: continue
                val deviceId = device.optString("device_id")
                if (deviceId.isBlank() || deviceId in seen) continue
                seen += deviceId
                val (profile, parsed) = profileByDevice[deviceId] ?: (JSONObject() to JSONObject())
                add(deviceId, device, profile, parsed)
            }
            for ((deviceId, pair) in profileByDevice) {
                if (deviceId in seen) continue
                add(deviceId, JSONObject(), pair.first, pair.second)
            }
            return cameras
        }

        fun cameraIp(device: JSONObject, profile: JSONObject, parsed: JSONObject): String {
            val candidate = firstString(
                device,
                listOf("ip", "local_ip", "lan_ip"),
            ) ?: firstString(profile, listOf("ip", "local_ip", "lan_ip"))
                ?: firstString(parsed, listOf("ip", "local_ip", "lan_ip"))
            asPrivateV4(candidate)?.let { return it }
            val haystack = "$profile $device"
            val regex = Regex("""\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b""")
            regex.findAll(haystack).forEach { match ->
                asPrivateV4(match.value)?.let { return it }
            }
            return ""
        }

        fun firstString(obj: JSONObject, keys: List<String>): String? {
            for (key in keys) {
                val value = obj.optString(key).trim()
                if (value.isNotEmpty()) return value
            }
            return null
        }

        fun asPrivateV4(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val parts = text.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 }) return null
            val private = when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                else -> false
            }
            return if (private) text else null
        }

        fun friendlyCognito(exception: Exception?): String {
            val combined = listOfNotNull(exception?.message, exception?.toString()).joinToString(" ").uppercase()
            return when {
                "SMS QUOTA" in combined || "USERLAMBDAVALIDATION" in combined ->
                    "Cubo could not send an SMS code (quota exceeded). Try again later, or use an authenticator code."
                "NOTAUTHORIZED" in combined || "INVALIDPASSWORD" in combined || "USERNOTFOUND" in combined ->
                    "Incorrect Cubo email or password."
                "CODEMISMATCH" in combined ->
                    "That verification code is incorrect."
                "EXPIREDCODE" in combined ->
                    "That verification code expired. Fetch cameras again to get a new one."
                "TOOMANYREQUESTS" in combined || "LIMITEXCEEDED" in combined ->
                    "Too many login attempts. Wait a minute and try again."
                "PASSWORDRESETREQUIRED" in combined ->
                    "This Cubo account must reset its password in the official Cubo app first."
                else -> exception?.message?.takeIf { it.isNotBlank() } ?: "Cubo login failed."
            }
        }

        fun friendlyHttp(code: Int, body: String): String = when (code) {
            401, 403 -> "Cubo rejected the session. Check the email and password."
            else -> "Cubo API HTTP $code" + body.take(160).let { if (it.isBlank()) "" else ": $it" }
        }

        fun unsupportedChallenge(name: String?): String = when (name) {
            "NEW_PASSWORD_REQUIRED" ->
                "This Cubo account must set a new password in the official Cubo app first."
            null, "" -> "Cognito login did not complete."
            else -> "Unsupported Cognito step ($name). Use the official Cubo app to finish sign-in, then try again."
        }
    }
}

private sealed class CognitoStep {
    data class Session(val accessToken: String, val idToken: String) : CognitoStep()
    data class Mfa(val hint: String) : CognitoStep()
}
