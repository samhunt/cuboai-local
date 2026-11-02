@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.cuboai.local.ui

import android.app.Application
import android.view.TextureView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import android.util.Log
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
import com.cuboai.local.data.TsHub
import com.cuboai.local.data.TsHubDataSource
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
import kotlinx.coroutines.yield
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
    val cameras: List<CameraCredentials> = emptyList(),
    val selectedCameraId: String? = null,
)

class CuboViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CredentialStore(application)
    private val cloud = CuboCloudClient(application)
    private val bridge = PythonBridge()
    private val pythonDispatcher = Executors.newSingleThreadExecutor {
        Thread(it, "cuboai-python").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var sessionJob: Job? = null
    private val savedCameras = store.loadCameras()
    private val savedSelected = savedCameras.find { it.uid == store.loadSelectedUid() }
        ?: savedCameras.firstOrNull()

    private val _state = MutableStateFlow(
        if (savedSelected != null) {
            UiState(
                form = savedSelected,
                cameras = savedCameras,
                selectedCameraId = savedSelected.uid,
                loginMode = LoginMode.Manual,
                remember = true,
                screen = if (savedSelected.canConnect) Screen.Monitor else Screen.Login,
                busy = savedSelected.canConnect,
            )
        } else {
            UiState()
        },
    )
    val state: StateFlow<UiState> = _state

    val player: ExoPlayer = ExoPlayer.Builder(application)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_000, 5_000, 250, 500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("cuboai-player", "playback error", error)
                        _state.update { it.copy(error = error.localizedMessage ?: error.errorCodeName) }
                        viewModelScope.launch {
                            delay(800)
                            if (_state.value.screen == Screen.Monitor && _state.value.streamReady) {
                                attachPlayer()
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        Log.i(
                            "cuboai-player",
                            "first frame ${player.videoSize.width}x${player.videoSize.height}",
                        )
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.i("cuboai-player", "state=$playbackState")
                    }
                },
            )
        }

    private var pollJob: Job? = null
    private var videoTexture: TextureView? = null

    init {
        if (savedSelected?.canConnect == true) {
            viewModelScope.launch {
                yield()
                Log.i("cuboai-session", "auto-connect ${savedSelected.uid}")
                connect()
            }
        }
    }

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
                it.copy(busy = true, error = null, mfaRequired = false, mfaCode = "")
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

    fun selectCamera(camera: CameraCredentials) {
        if (_state.value.screen == Screen.Monitor) {
            switchCamera(camera)
            return
        }
        _state.update {
            it.copy(selectedCameraId = camera.uid, form = camera, error = null)
        }
        persistIfRemembered()
    }

    fun switchCamera(camera: CameraCredentials) {
        val current = _state.value
        if (camera.uid == current.form.uid && current.streamReady) return
        if (!camera.canConnect) {
            _state.update { it.copy(error = "Camera LAN IP is required for ${camera.label}.") }
            return
        }
        _state.update {
            it.copy(
                form = camera,
                selectedCameraId = camera.uid,
                error = null,
            )
        }
        persistIfRemembered()
        startConnect(stayOnMonitorIfFail = true)
    }

    fun bindVideoTexture(view: TextureView) {
        videoTexture = view
        player.setVideoTextureView(view)
        Log.i("cuboai-player", "texture bound")
    }

    fun unbindVideoTexture(view: TextureView) {
        if (videoTexture !== view) return
        videoTexture = null
        player.setVideoTextureView(null)
        Log.i("cuboai-player", "texture unbound")
    }

    fun connect() {
        startConnect(stayOnMonitorIfFail = false)
    }

    private fun startConnect(stayOnMonitorIfFail: Boolean) {
        val creds = _state.value.form
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, streamReady = false) }
            Log.i("cuboai-session", "connect ${creds.uid} ${creds.ip}")
            stopPlayback()
            val result = withContext(pythonDispatcher) {
                runCatching { bridge.stopStream() }
                runCatching { bridge.disconnect() }
                bridge.connect(creds)
            }
            result.fold(
                onSuccess = {
                    if (_state.value.remember) persistIfRemembered() else store.clear()
                    val songs = withContext(pythonDispatcher) { bridge.lullabies().getOrDefault(emptyList()) }
                    val stream = withContext(pythonDispatcher) { bridge.startStreamServer() }
                    stream.fold(
                        onSuccess = {
                            _state.update {
                                it.copy(
                                    screen = Screen.Monitor,
                                    busy = false,
                                    songs = songs,
                                    streamReady = true,
                                    error = null,
                                    status = CameraStatus(),
                                )
                            }
                            delay(120)
                            attachPlayer()
                            startPolling()
                            launch {
                                delay(3_000)
                                if (isActive && _state.value.screen == Screen.Monitor) {
                                    refreshStatus()
                                }
                            }
                        },
                        onFailure = { err ->
                            withContext(pythonDispatcher) { runCatching { bridge.disconnect() } }
                            failConnect(err.message, stayOnMonitorIfFail)
                        },
                    )
                },
                onFailure = { err ->
                    failConnect(err.message, stayOnMonitorIfFail)
                },
            )
        }
    }

    fun disconnect(backToLogin: Boolean = true) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            stopPlayback()
            withContext(pythonDispatcher) {
                runCatching { bridge.stopStream() }
                runCatching { bridge.disconnect() }
            }
            if (backToLogin) {
                _state.update {
                    it.copy(
                        screen = Screen.Login,
                        busy = false,
                        streamReady = false,
                        status = CameraStatus(),
                        error = null,
                    )
                }
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

    private fun attachPlayer() {
        videoTexture?.let { player.setVideoTextureView(it) }
        val mediaItem = MediaItem.Builder()
            .setUri(TsHubDataSource.URI)
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
            .build()
        val flags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        val tsExtractors = ExtractorsFactory {
            arrayOf(
                TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    flags,
                    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
                ),
            )
        }
        player.setMediaSource(
            ProgressiveMediaSource.Factory(TsHubDataSource.Factory(), tsExtractors)
                .setContinueLoadingCheckIntervalBytes(32 * 1024)
                .createMediaSource(mediaItem),
        )
        player.prepare()
        player.playWhenReady = true
        Log.i("cuboai-player", "attached TsHub available=${TsHub.INSTANCE.available()}")
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
                        _state.update { current ->
                            val existing = current.cameras.associateBy { it.uid }
                            val fetched = auth.cameras.map { it.toCredentials(existing[it.uid]) }
                            val fetchedUids = fetched.map { it.uid }.toSet()
                            val cameras = fetched + current.cameras.filter { it.uid !in fetchedUids }
                            val selected = cameras.find { it.uid == current.selectedCameraId }
                                ?: cameras.find { it.uid == current.form.uid }
                                ?: cameras.first()
                            current.copy(
                                busy = false,
                                mfaRequired = false,
                                mfaCode = "",
                                cuboPassword = "",
                                cameras = cameras,
                                selectedCameraId = selected.uid,
                                form = selected.copy(ip = selected.ip.ifBlank { current.form.ip }),
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
        if (!state.remember || form.uid.isBlank() || form.account.isBlank() || form.password.isBlank()) {
            return
        }
        val cameras = state.cameras
            .map { if (it.uid == form.uid) form else it }
            .let { list -> if (list.any { it.uid == form.uid }) list else list + form }
            .ifEmpty { listOf(form) }
        store.saveCameras(cameras, form.uid)
        _state.update { current ->
            if (current.cameras == cameras && current.selectedCameraId == form.uid) current
            else current.copy(cameras = cameras, selectedCameraId = form.uid)
        }
    }

    private fun CloudCamera.toCredentials(existing: CameraCredentials?): CameraCredentials =
        CameraCredentials(
            name = name.ifBlank { existing?.name ?: "Camera" },
            ip = ip.ifBlank { existing?.ip.orEmpty() },
            uid = uid,
            account = account,
            password = password,
        )

    private fun failConnect(message: String?, stayOnMonitor: Boolean) {
        _state.update {
            it.copy(
                busy = false,
                error = message,
                screen = if (stayOnMonitor) Screen.Monitor else Screen.Login,
                streamReady = false,
            )
        }
    }

    private fun stopPlayback() {
        pollJob?.cancel()
        pollJob = null
        player.stop()
        player.clearMediaItems()
        // Keep the TextureView. Auto-connect and camera switch stay on Monitor,
        // so Compose will not recreate it; clearing the surface leaves a black video.
    }

    private fun command(block: PythonBridge.() -> Result<Unit>) {
        viewModelScope.launch {
            val result = withContext(pythonDispatcher) { bridge.block() }
            result.onFailure { err -> _state.update { it.copy(error = err.message) } }
            refreshStatus()
        }
    }
}
