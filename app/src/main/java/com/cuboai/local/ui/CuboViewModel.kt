package com.cuboai.local.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cuboai.local.data.CameraCredentials
import com.cuboai.local.data.CameraStatus
import com.cuboai.local.data.CloudAuthResult
import com.cuboai.local.data.CloudCamera
import com.cuboai.local.data.CredentialStore
import com.cuboai.local.data.CuboCloudClient
import com.cuboai.local.data.LoginMode
import com.cuboai.local.data.LullabySong
import com.cuboai.local.data.PythonBridge
import com.cuboai.local.data.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors

data class UiState(
    val screen: Screen = Screen.Login,
    val form: CameraCredentials = CameraCredentials(),
    val remember: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val status: CameraStatus = CameraStatus(),
    val songs: List<LullabySong> = emptyList(),
    val streamReady: Boolean = false,
    val loginMode: LoginMode = LoginMode.CuboAccount,
    val cuboEmail: String = "",
    val cuboPassword: String = "",
    val mfaCode: String = "",
    val mfaRequired: Boolean = false,
    val mfaHint: String = "",
    val cameras: List<CloudCamera> = emptyList(),
    val selectedCameraId: String? = null,
)

class CuboViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CredentialStore(application)
    private val cloud = CuboCloudClient(application)
    private val bridge = PythonBridge()
    private val pythonDispatcher = Executors.newSingleThreadExecutor {
        Thread(it, "cuboai-python").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(
        store.credentials?.let { saved ->
            UiState(form = saved, loginMode = LoginMode.Manual)
        } ?: UiState(),
    )
    val state: StateFlow<UiState> = _state

    val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        playWhenReady = true
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private var pollJob: Job? = null

    fun updateForm(transform: (CameraCredentials) -> CameraCredentials) {
        _state.update { it.copy(form = transform(it.form), error = null) }
        persistIfRemembered()
    }

    fun setRemember(value: Boolean) {
        _state.update { it.copy(remember = value) }
        if (value) persistIfRemembered() else store.clear()
    }

    fun setLoginMode(mode: LoginMode) {
        _state.update { it.copy(loginMode = mode, error = null) }
    }

    fun setCuboEmail(value: String) {
        _state.update { it.copy(cuboEmail = value, error = null) }
    }

    fun setCuboPassword(value: String) {
        _state.update { it.copy(cuboPassword = value, error = null) }
    }

    fun setMfaCode(value: String) {
        _state.update { it.copy(mfaCode = value, error = null) }
    }

    fun fetchFromCubo() {
        val email = _state.value.cuboEmail
        val password = _state.value.cuboPassword
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, error = null, mfaRequired = false, mfaCode = "", cameras = emptyList())
            }
            val result = withContext(Dispatchers.IO) { runCatching { cloud.signIn(email, password) } }
            applyCloudResult(result)
        }
    }

    fun submitMfa() {
        val code = _state.value.mfaCode
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { cloud.completeMfa(code) } }
            applyCloudResult(result)
        }
    }

    fun selectCamera(camera: CloudCamera) {
        _state.update { current ->
            current.copy(
                selectedCameraId = camera.deviceId,
                form = current.form.withCamera(camera),
                error = null,
            )
        }
        persistIfRemembered()
    }

    fun connect() {
        val creds = _state.value.form
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = withContext(pythonDispatcher) { bridge.connect(creds) }
            result.fold(
                onSuccess = {
                    if (_state.value.remember) store.credentials = creds else store.clear()
                    val songs = withContext(pythonDispatcher) { bridge.lullabies().getOrDefault(emptyList()) }
                    val stream = withContext(pythonDispatcher) { bridge.startStreamServer() }
                    stream.fold(
                        onSuccess = { url ->
                            attachPlayer(url)
                            _state.update {
                                it.copy(
                                    screen = Screen.Monitor,
                                    busy = false,
                                    songs = songs,
                                    streamReady = true,
                                )
                            }
                            startPolling()
                            refreshStatus()
                        },
                        onFailure = { err ->
                            withContext(pythonDispatcher) { bridge.disconnect() }
                            _state.update { it.copy(busy = false, error = err.message) }
                        },
                    )
                },
                onFailure = { err ->
                    _state.update { it.copy(busy = false, error = err.message) }
                },
            )
        }
    }

    fun disconnect(backToLogin: Boolean = true) {
        pollJob?.cancel()
        pollJob = null
        player.stop()
        player.clearMediaItems()
        viewModelScope.launch {
            withContext(pythonDispatcher) {
                bridge.stopStream()
                bridge.disconnect()
            }
            if (backToLogin) {
                _state.update { it.copy(screen = Screen.Login, busy = false, streamReady = false) }
            }
        }
    }

    fun setNightLight(on: Boolean) = command { bridge.setNightLight(on) }
    fun setBrightness(value: Int) = command { bridge.setBrightness(value) }
    fun setSleepMode(enabled: Boolean) = command { bridge.setSleepMode(enabled) }
    fun playLullaby(uuid: String) = command { bridge.setLullaby(uuid) }
    fun stopLullaby() = command { bridge.stopLullaby() }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun showLicense() {
        _state.update { current ->
            val from = current.screen
            if (from is Screen.License) current
            else current.copy(screen = Screen.License(from))
        }
    }

    fun hideLicense() {
        _state.update { current ->
            val from = (current.screen as? Screen.License)?.from ?: Screen.Login
            current.copy(screen = from)
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        player.release()
        try {
            runBlocking(pythonDispatcher) {
                withTimeout(2_000) { bridge.disconnect() }
            }
        } catch (_: Exception) {
        } finally {
            pythonDispatcher.close()
        }
        super.onCleared()
    }

    private fun attachPlayer(url: String) {
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent("cuboai-local")
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .build()
        player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem))
        player.prepare()
        player.playWhenReady = true
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                refreshStatus()
            }
        }
    }

    private suspend fun refreshStatus() {
        val result = withContext(pythonDispatcher) { bridge.sensors() }
        result.onSuccess { status ->
            _state.update { it.copy(status = status) }
        }
    }

    private fun applyCloudResult(result: Result<CloudAuthResult>) {
        result.fold(
            onSuccess = { auth ->
                when (auth) {
                    is CloudAuthResult.MfaRequired -> {
                        _state.update {
                            it.copy(
                                busy = false,
                                mfaRequired = true,
                                mfaHint = auth.hint,
                                cuboPassword = "",
                            )
                        }
                    }
                    is CloudAuthResult.Cameras -> {
                        val first = auth.cameras.first()
                        _state.update { current ->
                            val selected = auth.cameras.find { it.deviceId == current.selectedCameraId } ?: first
                            current.copy(
                                busy = false,
                                mfaRequired = false,
                                mfaCode = "",
                                cuboPassword = "",
                                cameras = auth.cameras,
                                selectedCameraId = selected.deviceId,
                                form = current.form.withCamera(selected),
                            )
                        }
                        persistIfRemembered()
                    }
                }
            },
            onFailure = { err ->
                _state.update { it.copy(busy = false, error = err.message) }
            },
        )
    }

    private fun persistIfRemembered() {
        val state = _state.value
        val form = state.form
        if (state.remember && form.uid.isNotBlank() && form.account.isNotBlank() && form.password.isNotBlank()) {
            store.credentials = form
        }
    }

    private fun CameraCredentials.withCamera(camera: CloudCamera): CameraCredentials = copy(
        name = camera.name.ifBlank { name },
        uid = camera.uid,
        account = camera.account,
        password = camera.password,
        ip = camera.ip.ifBlank { ip },
    )

    private fun command(block: PythonBridge.() -> Result<Unit>) {
        viewModelScope.launch {
            val result = withContext(pythonDispatcher) { bridge.block() }
            result.onFailure { err -> _state.update { it.copy(error = err.message) } }
            refreshStatus()
        }
    }
}
