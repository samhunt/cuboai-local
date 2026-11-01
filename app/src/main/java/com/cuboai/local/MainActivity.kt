package com.cuboai.local

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuboai.local.data.Screen
import com.cuboai.local.ui.CuboViewModel
import com.cuboai.local.ui.LicenseScreen
import com.cuboai.local.ui.LoginScreen
import com.cuboai.local.ui.MonitorScreen
import com.cuboai.local.ui.theme.CuboLocalTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CuboViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CuboLocalTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    val padded = Modifier.padding(inner)
                    when (state.screen) {
                        Screen.Login -> LoginScreen(
                            state = state,
                            onFormChange = viewModel::updateForm,
                            onRememberChange = viewModel::setRemember,
                            onLoginMode = viewModel::setLoginMode,
                            onCuboEmail = viewModel::setCuboEmail,
                            onCuboPassword = viewModel::setCuboPassword,
                            onMfaCode = viewModel::setMfaCode,
                            onFetchCameras = viewModel::fetchFromCubo,
                            onSubmitMfa = viewModel::submitMfa,
                            onSelectCamera = viewModel::selectCamera,
                            onConnect = viewModel::connect,
                            onShowLicense = viewModel::showLicense,
                            modifier = padded,
                        )
                        Screen.Monitor -> MonitorScreen(
                            state = state,
                            player = viewModel.player,
                            onNightLight = viewModel::setNightLight,
                            onBrightness = viewModel::setBrightness,
                            onSleepMode = viewModel::setSleepMode,
                            onPlaySong = viewModel::playLullaby,
                            onStopSong = viewModel::stopLullaby,
                            onDisconnect = { viewModel.disconnect() },
                            onShowLicense = viewModel::showLicense,
                            modifier = padded,
                        )
                        is Screen.License -> LicenseScreen(
                            onBack = viewModel::hideLicense,
                            modifier = padded,
                        )
                    }
                }
            }
        }
    }
}
